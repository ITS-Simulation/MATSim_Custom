import math
from typing import Literal

import pandas as pd
import ast

from pandas import DataFrame

from scoring.utility import yaml_parser, dataframe_to_file, read_dataframe, json_loader, json_writer

type Cache = DataFrame | dict | None
type LOSAggMode = Literal["operator_veh_time", "operator_load", "passenger_time", "passenger_trip"]

class LOSCalculator:
    def __init__(self, config: str) -> None:
        self._config = yaml_parser(config)
        self._debug = self._config["mode"] == "debug"
        self._debug_score_history = {}  # Debug history cho tất cả các phương thức
        self.__load_los_data()
        self.__load_avg_psg_trip_len()

        self._los_cache: Cache = None # Cache để lưu kết quả tính toán LOS nếu cần thiết

    def __load_avg_psg_trip_len(self):
        avg_trip_len_file = self._config["files"]["data"]["avg_trip_length"]
        data = json_loader(avg_trip_len_file)
        self._psg_avg_travel_len = data["avg_passenger_trip_length"]

    def __load_los_data(self) -> None:
        filtered_los_data = self._config["files"]["los_data"]["filtered"]
        cols = [
            "link_id", "ewt", "veh_flow", "avg_speed", "avg_bus_speed", "avg_load_factor",
            "length", "bus_frequency",
            "ewt_per_line", "avg_bus_speed_per_line", "avg_load_factor_per_line"
        ]
        dtype = {
            "link_id": str,
            "ewt": float,
            "veh_flow": float,
            "avg_speed": float,
            "avg_bus_speed": float,
            "avg_load_factor": float,
            "length": float,
            "bus_frequency": float,
            "ewt_per_line": str,
            "avg_bus_speed_per_line": str,
            "avg_load_factor_per_line": str,
        }
        self._los_data = read_dataframe(filtered_los_data, self._debug, usecols=cols, dtype=dtype, index_col=0)

    @staticmethod
    def __calculate_headway_factor(freq: float) -> float:
        pow_factor = -1.434 / (freq + 0.001)
        return 4 * (math.e ** pow_factor)

    @staticmethod
    def __calculate_load_factor_weight(lf: float) -> float:
        if lf <= 0.8:
            return 1.0
        base_factor = 1 + 4 * (lf - 0.8) / (4.2 * lf)
        if lf <= 1.0:
            return base_factor
        return base_factor + (lf - 1.0) * (6.5 + 5 * (lf - 1.0)) / (4.2 * lf)

    def __calculate_travel_time_factor(self, perc_tt: float) -> float:
        elas = self._config["scoring"]["wait_ride"]["elas"]
        base_tt = self._config["scoring"]["wait_ride"]["base_travel_time"]
        nom = (elas - 1) * base_tt - (elas + 1) * perc_tt
        denom = (elas - 1) * perc_tt - (elas + 1) * base_tt
        if denom == 0:
            raise ValueError("Denominator in travel time factor calculation is zero.")
        return nom / denom

    def __calculate_amenity(self) -> float:
        shelter = self._config["scoring"]["amenity"]["shelter"]
        bench = self._config["scoring"]["amenity"]["bench"]

        shelter_ratios = self._config["scoring"]["amenity"]["shelter_rate"]
        bench_ratios = self._config["scoring"]["amenity"]["bench_rate"]

        return (shelter * shelter_ratios + bench * bench_ratios) / self._psg_avg_travel_len

    @staticmethod
    def __get_debug_key(row_id) -> str:
        """Chuyển đổi row_id thành key phù hợp cho JSON (tuple -> string)."""
        if isinstance(row_id, tuple):
            return "|".join(str(x) for x in row_id)
        return str(row_id)

    def __calculate_wait_ride_score(self, row) -> float:
        """
        Tính điểm Transit wait-ride (S_w-r) = f_h * f_tt
        - f_h: Headway Factor (phụ thuộc bus_frequency)
        - f_tt: Travel Time Factor (phụ thuộc perceived travel time)
        """
        # Lấy ID để logging debug (hỗ trợ cả single-index và multi-index)
        debug_key = self.__get_debug_key(row.name)

        fh = self.__calculate_headway_factor(row["bus_frequency"])
        fpl = self.__calculate_load_factor_weight(row["avg_load_factor"])
        bus_speed = row["avg_bus_speed"] * 3.6  # m/s -> km/h
        ewt = (row["ewt"] / 60) / (self._psg_avg_travel_len / 1000)
        amenity = self.__calculate_amenity()

        """
        Calculate travel time factor (f_tt)
        To calculate f_tt, we need to find the Perceived Travel Time (T_ptt - or perc_tt)
        T_ptt = f_pl * (60 / S) + 2 * T_ex - T_at
        where:
        - f_pl: Passenger Weighting Load Factor
        - S: Average Bus Speed (km/h)
        - T_ex: Average Excess Wait Time (minutes)
        - T_at: Perceived Amenity Time (minutes)
        """
        perc_tt = fpl * (60 / bus_speed) + 2 * ewt - amenity
        ftt = self.__calculate_travel_time_factor(perc_tt)
        wait_ride_score = fh * ftt

        # Debug: log component scores
        if self._debug:
            self._debug_score_history[debug_key] = {
                "input": {
                    "bus_frequency": row["bus_frequency"],
                    "avg_load_factor": row["avg_load_factor"],
                    "avg_bus_speed_mps": row["avg_bus_speed"],
                    "ewt_seconds": row["ewt"],
                    "veh_flow": row["veh_flow"],
                    "avg_speed_mps": row["avg_speed"],
                },
                "wait_ride": {
                    "headway_factor": fh,
                    "load_factor_weight": fpl,
                    "bus_speed_kmh": bus_speed,
                    "ewt_normalized": ewt,
                    "amenity_time": amenity,
                    "perceived_travel_time": perc_tt,
                    "travel_time_factor": ftt,
                    "wait_ride_score": wait_ride_score,
                }
            }

        return wait_ride_score

    def __calculate_ped_score(self, row) -> float:
        """
        Tính Pedestrian Environment Score (I_p) = 6.0468 + f_w + f_v + f_s
        - f_w: Cross-section adjustment factor
        - f_v: Traffic volume adjustment factor
        - f_s: Traffic speed adjustment factor
        """
        debug_key = self.__get_debug_key(row.name)

        veh_flow = row["veh_flow"]
        fv = 0.0091 * veh_flow / 4

        avg_speed = row["avg_speed"] * 3.6 / 1.6  # m/s -> mph
        fs = 4 * ((avg_speed / 100) ** 2)

        """
        Calculate f_w based on cross-section data.
        f_w = -1.2276 * ln(W_v + 0.5 * W_1 + 50 * p_pk + W_buff * f_b + W_aA * f_sw)
        where:
        W_v: effective total width of outside through lanes, bike lanes, and parking lanes (ft)
        W_1: effective total width of combined bike lanes and parking lanes (ft)
        p_pk: proportion of on-street parking occupancy (0 to 1)
        W_buff: effective total width of buffers between travel lanes and sidewalks (ft)
        f_b: buffer factor based on buffer type
        W_aA: adjusted effective sidewalk width (ft) 
        (Calculated based on available sidewalk width (W_A) - W_aA = min(W_A, 10) (ft))
        f_sw: sidewalk width coefficient = 6.0 - 0.3 * W_aA

        For now, all links have the same factors as we do not have the data to calculate them.
        TODO: Implement calculation of f_w based on actual cross-section data.
        """
        w_ol = float(self._config["scoring"]["ped_env"]["outside_lane_width"])
        w_bl = float(self._config["scoring"]["ped_env"]["bike_lane_width"])
        w_os = float(self._config["scoring"]["ped_env"]["parking_lane_width"])
        adj_w_os = max(0.0, w_os - 1.5)
        p_pk = float(self._config["scoring"]["ped_env"]["street_parking"])
        out_lane_veh_threshold = float(self._config["scoring"]["ped_env"]["volume_threshold"])

        w_1 = 10 if p_pk >= 0.25 else w_bl + adj_w_os
        w_t = w_ol + w_bl + adj_w_os if (p_pk == 0.0) else w_ol + w_bl
        w_v = w_t if veh_flow > out_lane_veh_threshold else w_t * (2 - 0.005 * veh_flow)

        w_buff = float(self._config["scoring"]["ped_env"]["sidewalk_buffer"])
        f_b = float(self._config["scoring"]["ped_env"]["buffer_coeff"])
        sidewalk_buf_idx = w_buff * f_b

        w_a = float(self._config["scoring"]["ped_env"]["sidewalk_width"])
        w_aa = min(w_a, 10)
        sidewalk_width_idx = w_aa * (6.0 - 0.3 * w_aa)

        fw = -1.2276 * math.log(w_v + 0.5 * w_1 + 50 * p_pk + sidewalk_buf_idx + sidewalk_width_idx)

        ped_score = 6.0468 + fw + fv + fs

        # Debug: log pedestrian component scores
        if self._debug and debug_key in self._debug_score_history:
            self._debug_score_history[debug_key]["ped_env"] = {
                "f_v_traffic_volume": fv,
                "f_s_traffic_speed": fs,
                "avg_speed_mph": avg_speed,
                "w_v_effective_width": w_v,
                "w_1_bike_parking_width": w_1,
                "sidewalk_buffer_idx": sidewalk_buf_idx,
                "sidewalk_width_idx": sidewalk_width_idx,
                "f_w_cross_section": fw,
                "ped_score": ped_score,
            }

        return ped_score

    @staticmethod
    def __calculate_los_and_grade(df: pd.DataFrame) -> None:
        """Tính LOS và phân loại grade từ các điểm wait-ride và ped."""
        df["los"] = 6.0 - 1.5 * df["wait_ride_score"] + 0.15 * df["ped_score"]
        df["los_grade"] = pd.cut(
            df["los"],
            bins=[-float('inf'), 2.0, 2.75, 3.5, 4.25, 5.0, float('inf')],
            labels=["A", "B", "C", "D", "E", "F"]
        )

    def __output_results(self, df: pd.DataFrame, output_key: str, index_label, debug_path: str = None) -> None:
        """Xuất kết quả LOS ra file."""
        final_los = df[["wait_ride_score", "ped_score", "los", "los_grade"]]
        if self._debug and debug_path:
            dataframe_to_file(df, debug_path, self._debug, index_label=index_label)
        dataframe_to_file(final_los, self._config["files"]["los_data"][output_key], self._debug, index_label=index_label)

    def __finalize_debug_history(self, df: pd.DataFrame, output_path: str) -> None:
        """Thêm điểm cuối cùng vào debug history và xuất ra JSON."""
        if not self._debug:
            return

        for row_idx in df.index:
            debug_key = self.__get_debug_key(row_idx)
            if debug_key not in self._debug_score_history:
                continue
            los = df.loc[row_idx]
            self._debug_score_history[debug_key]["final"] = {
                "wait_ride_score": los["wait_ride_score"],
                "ped_score": los["ped_score"],
                "los": los["los"],
                "los_grade": str(los["los_grade"]),
            }
        json_writer(self._debug_score_history, output_path)

    def run_calculation(self) -> None:
        """Tính toán LOS trên trục [link_id]."""
        # Reset debug history cho mỗi lần chạy
        self._debug_score_history = {}

        # Tính điểm sử dụng các hàm dùng chung
        self._los_data["wait_ride_score"] = self._los_data.apply(self.__calculate_wait_ride_score, axis=1)
        self._los_data["ped_score"] = self._los_data.apply(self.__calculate_ped_score, axis=1)
        self.__calculate_los_and_grade(self._los_data)

        # Debug: xuất JSON với điểm cuối cùng
        self.__finalize_debug_history(self._los_data, "data/test/json/los_score_history.json")

        # Xuất kết quả
        self.__output_results(
            self._los_data, "scores", "link_id",
            debug_path="data/test/csv/los_score_history.csv"
        )

    def run_calculation_per_line(self) -> None:
        """
        Tính toán LOS trên trục [link_id, line_id].
        Mở rộng dữ liệu per-line thành các hàng riêng biệt,
        tính toán điểm wait-ride và ped cho từng cặp (link, line).
        """
        # Reset debug history cho mỗi lần chạy
        self._debug_score_history = {}

        df = self._los_data.copy()

        # Chuyển đổi các cột dictionary từ chuỗi JSON
        for col in ["ewt_per_line", "avg_bus_speed_per_line", "avg_load_factor_per_line"]:
            df[col] = df[col].apply(lambda x: ast.literal_eval(x) if isinstance(x, str) else x)

        # Mở rộng dữ liệu per-line thành các hàng riêng biệt
        rows = []
        for link_id, row in df.iterrows():
            ewt_per_line = row.get("ewt_per_line", {}) or {}
            speed_per_line = row.get("avg_bus_speed_per_line", {}) or {}
            lf_per_line = row.get("avg_load_factor_per_line", {}) or {}

            all_lines = set(ewt_per_line.keys()) | set(speed_per_line.keys()) | set(lf_per_line.keys())

            for line_id in all_lines:
                rows.append({
                    "link_id": link_id,
                    "line_id": line_id,
                    "ewt": ewt_per_line.get(line_id, row["ewt"]),
                    "avg_bus_speed": speed_per_line.get(line_id, row["avg_bus_speed"]),
                    "avg_load_factor": lf_per_line.get(line_id, row["avg_load_factor"]),
                    "bus_frequency": row["bus_frequency"],
                    "veh_flow": row["veh_flow"],
                    "avg_speed": row["avg_speed"],
                    "length": row["length"],
                })

        los_data_per_line = pd.DataFrame(rows)
        los_data_per_line = los_data_per_line.set_index(["link_id", "line_id"])

        if self._debug:
            dataframe_to_file(los_data_per_line, "data/test/csv/los_per_line_expanded.csv", self._debug, index_label=["link_id", "line_id"])

        # Tính điểm sử dụng các hàm dùng chung
        los_data_per_line["wait_ride_score"] = los_data_per_line.apply(self.__calculate_wait_ride_score, axis=1)
        los_data_per_line["ped_score"] = los_data_per_line.apply(self.__calculate_ped_score, axis=1)
        self.__calculate_los_and_grade(los_data_per_line)

        # Debug: xuất JSON với điểm cuối cùng
        self.__finalize_debug_history(los_data_per_line, "data/test/json/los_per_line_score_history.json")

        # Xuất kết quả
        self.__output_results(
            los_data_per_line, "line_scores", ["link_id", "line_id"],
            debug_path="data/test/csv/los_per_line_full.csv"
        )

    def aggregate_los_score(self, mode: LOSAggMode) -> float:
        if self._los_cache is None:
            score_file = self._config["files"]["los_data"]["scores"]
            cols = ["link_id", "los"]
            dtype = {
                "link_id": str,
                "los": float,
            }
            score_df = read_dataframe(score_file, self._debug, usecols=cols, dtype=dtype, index_col=0)

            link_data_file = self._config["files"]["metadata"]["link_data"]
            cols = ["link_id", "length"]
            dtype = {
                "link_id": str,
                "length": float
            }
            link_df = read_dataframe(link_data_file, self._debug, usecols=cols, dtype=dtype, index_col=0)

            load_data = self._config["files"]["data"]["load_history"]
            cols = ["link_id", "duration", "pax_seconds", "plan_cap"]
            dtype = {
                "link_id": str,
                "duration": float,
                "pax_seconds": float,
                "plan_cap": float,
            }
            load_df = read_dataframe(load_data, self._debug, usecols=cols, dtype=dtype)

            load_df = load_df.groupby(["link_id"]).agg(
                total_pax_seconds=("pax_seconds", "sum"),
                total_duration=("duration", "sum"),
                total_plan_cap=("plan_cap", "sum")
            )

            df = score_df.join([
                link_df,
                load_df
            ], how="inner")  # Chỉ giữ các link_id có trong cả 3 DataFrame
            self._los_cache = df
        else:
            df = self._los_cache

        if mode == "operator_load":
            df["operator_load_weight"] = df["length"] * df["total_plan_cap"]
            agg_los = (df["los"] * df["operator_load_weight"]).sum() / df["operator_load_weight"].sum()
        elif mode == "operator_veh_time":
            agg_los = (df["los"] * df["total_duration"]).sum() / df["total_duration"].sum()
        elif mode == "passenger_time":
            agg_los = (df["los"] * df["total_pax_seconds"]).sum() / df["total_pax_seconds"].sum()
        elif mode == "passenger_trip":
            df["avg_load"] = df["total_pax_seconds"] / df["total_duration"]
            df["passenger_trip_weight"] = df["length"] * df["avg_load"]
            agg_los = (df["los"] * df["passenger_trip_weight"]).sum() / df["passenger_trip_weight"].sum()
        else:
            raise ValueError(f"Invalid aggregation mode: {mode}")
        return agg_los