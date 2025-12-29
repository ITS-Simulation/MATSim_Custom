import pandas as pd

from scoring.utility import yaml_parser, dataframe_to_file, read_dataframe, json_writer
from lxml import etree

# Xử lý dữ liệu đầu ra của MATSim để trích xuất các chỉ số hiệu suất
# Phiên bản 1.0: Trung bình cộng toàn bộ thời gian mô phỏng, nhóm theo link (Đã xong)
# Phiên bản 1.1: Thêm thống kê theo tuyến xe buýt (Đã xong)
# Phiên bản 1.2: Nội suy EWT cho các link không có điểm dừng (Đã xong)
# Phiên bản 1.3: Tái cấu trúc tính toán hệ số tải với phương pháp trọng số mới (Đã xong)
# TODO: Phiên bản 2.0 - Thêm xử lý phụ thuộc thời gian (ví dụ: giờ cao điểm/thấp điểm)
class MATSimProcessor:
    def __init__(self, config: str):
        self._config = yaml_parser(config)
        self._debug = self._config["mode"] == "debug"
        self.hw_tolerance = float(self._config["scoring"]["wait_ride"]["headway_tolerance"])
        self.__load_bus_line_headway()
        self.__load_bus_vehicle_ids()

    def __load_bus_line_headway(self) -> None:
        headway_file = self._config["files"]["metadata"]["bus_headway"]
        dtype = {
            "line_id": str,
            "scheduled_headway": float,
        }

        df = read_dataframe(headway_file, self._debug, dtype=dtype)
        self.bus_line_headway = dict(zip(df["line_id"], df["scheduled_headway"]))

    def __load_bus_vehicle_ids(self) -> None:
        """Tải danh sách ID xe buýt từ file metadata."""
        bus_vehicles_file = self._config["files"]["metadata"]["bus_vehicles"]
        df = read_dataframe(bus_vehicles_file, self._debug)
        self._bus_vehicle_ids = set(df["vehicle_id"].tolist())

    def __compute_actual_travel_times(self, veh_flow_df: pd.DataFrame) -> tuple[dict, dict]:
        """
        Tính toán thời gian di chuyển thực tế từ dữ liệu veh_flow.
        Xử lý các bản ghi bị chia tách tại ranh giới giờ bằng cách phát hiện trip boundary.

        Args:
            veh_flow_df: DataFrame từ veh_flow với các cột cần thiết

        Returns:
            Tuple of (avg_travel_time_per_link_line, reference_speed_per_link)
        """
        # Lọc chỉ xe buýt
        df = veh_flow_df[veh_flow_df["vehicle_id"].isin(self._bus_vehicle_ids)].copy()

        # Sắp xếp theo timestamp để phát hiện trip boundary
        df = df.sort_values(["vehicle_id", "link_id", "slice_start"])

        # Phát hiện trip boundary: khoảng cách > 60s = chuyến mới
        df["time_gap"] = df.groupby(["vehicle_id", "link_id"])["slice_start"].diff()
        df["trip_boundary"] = (df["time_gap"] > 60).fillna(False).cumsum()

        # Tổng hợp duration theo từng chuyến riêng biệt
        trip_durations = df.groupby(
            ["vehicle_id", "link_id", "line_id", "trip_boundary"]
        ).agg(
            total_duration=("duration", "sum"),
            total_distance=("travel_distance", "sum"),
        ).reset_index()

        # Tính thời gian di chuyển trung bình theo [link_id, line_id]
        avg_time_per_link_line = (
            trip_durations.groupby(["link_id", "line_id"])["total_duration"]
            .mean()
            .to_dict()
        )

        # Tính reference speed (tốc độ trung bình toàn bộ xe) theo link
        link_stats = trip_durations.groupby("link_id").agg(
            sum_duration=("total_duration", "sum"),
            sum_distance=("total_distance", "sum"),
        )
        reference_speed_per_link = (
            link_stats["sum_distance"] / link_stats["sum_duration"]
        ).to_dict()

        if self._debug:
            print(f"Computed travel times for {len(avg_time_per_link_line)} link-line pairs")
            print(f"Reference speed computed for {len(reference_speed_per_link)} links")

        return avg_time_per_link_line, reference_speed_per_link

    def __propagate_ewt_along_routes(
        self,
        transit_tree: etree._ElementTree,
        ewt_links_map: dict,
        ewt_lines_map: dict,
        stop_links: set,
        all_bus_links: set,
        link_length_map: dict,
        avg_travel_time: dict,
        reference_speed: dict,
    ) -> None:
        """
        Lan truyền EWT dọc theo các tuyến transit sử dụng thời gian di chuyển thực tế.
        
        Args:
            transit_tree: Cây XML của transit_schedule
            ewt_links_map: Dict EWT theo link (sẽ được cập nhật)
            ewt_lines_map: Dict EWT theo link-line (sẽ được cập nhật)
            stop_links: Set các link có điểm dừng
            all_bus_links: Set các link có xe buýt đi qua
            link_length_map: Dict độ dài link
            avg_travel_time: Dict thời gian di chuyển trung bình theo (link_id, line_id)
            reference_speed: Dict tốc độ tham chiếu theo link_id
        """
        stop_to_link = {
            stop.get("id"): stop.get("linkRefId")
            for stop in transit_tree.findall(".//stopFacility")
            if stop.get("id") and stop.get("linkRefId")
        }

        for line in transit_tree.findall("transitLine"):
            line_id = line.get("id")
            for route in line.findall("transitRoute"):
                route_links = route.xpath(".//route/link/@refId")
                stop_ids = route.xpath(".//routeProfile/stop/@refId")
                route_stop_links = {stop_to_link[s] for s in stop_ids if s in stop_to_link}

                # === PHASE 1: Tìm index của điểm dừng đầu tiên có EWT đo được ===
                first_measured_idx = None
                for i, link_id in enumerate(route_links):
                    if link_id in route_stop_links and link_id in ewt_links_map:
                        first_measured_idx = i
                        break

                if first_measured_idx is None:
                    continue  # Route này không có điểm dừng nào có EWT đo được

                # === PHASE 2: Backward sweep từ điểm dừng đầu tiên về đầu route ===
                first_ewt = ewt_links_map[route_links[first_measured_idx]]
                first_line_ewt = ewt_lines_map.get(route_links[first_measured_idx], {}).get(line_id, first_ewt)

                # Duyệt ngược từ first_measured_idx - 1 về 0
                current_ewt = first_ewt
                current_line_ewt = first_line_ewt
                for i in range(first_measured_idx - 1, -1, -1):
                    link_id = route_links[i]
                    if link_id not in all_bus_links:
                        continue
                    if link_id in ewt_links_map:
                        continue  # Đã có EWT

                    # Backward: không tích lũy delay, chỉ copy EWT
                    if link_id not in ewt_links_map:
                        ewt_links_map[link_id] = current_ewt
                        ewt_lines_map[link_id] = {}
                    if line_id not in ewt_lines_map.get(link_id, {}):
                        ewt_lines_map[link_id][line_id] = current_line_ewt

                # === PHASE 3: Forward sweep từ điểm dừng đầu tiên đến cuối route ===
                current_ewt = first_ewt
                current_line_ewt = first_line_ewt
                for i in range(first_measured_idx + 1, len(route_links)):
                    link_id = route_links[i]

                    # Nếu gặp anchor (link đã có EWT) -> reset current
                    if link_id in ewt_links_map:
                        current_ewt = ewt_links_map[link_id]
                        current_line_ewt = ewt_lines_map.get(link_id, {}).get(line_id, current_ewt)
                        # Cập nhật line-level nếu chưa có
                        if line_id not in ewt_lines_map.get(link_id, {}):
                            ewt_lines_map[link_id][line_id] = current_line_ewt
                        continue

                    if link_id not in all_bus_links:
                        continue

                    # Tính delay delta dựa trên travel time
                    delay_delta = 0.0
                    link_len = link_length_map.get(link_id, 0)
                    ref_speed = reference_speed.get(link_id)
                    actual_time = avg_travel_time.get((link_id, line_id))
                    if link_len > 0 and ref_speed and ref_speed > 0 and actual_time:
                        expected_time = link_len / ref_speed
                        delay_delta = actual_time - expected_time

                    # Lan truyền EWT
                    propagated_ewt = current_ewt + delay_delta
                    propagated_line_ewt = (current_line_ewt or current_ewt) + delay_delta

                    # Cập nhật maps
                    if link_id not in ewt_links_map:
                        ewt_links_map[link_id] = propagated_ewt
                        ewt_lines_map[link_id] = {}
                    if line_id not in ewt_lines_map.get(link_id, {}):
                        ewt_lines_map[link_id][line_id] = propagated_line_ewt

                    current_ewt = propagated_ewt
                    current_line_ewt = propagated_line_ewt

    def process_avg_psg_trip_len(self) -> float:
        """
        Tính toán độ dài chuyến đi trung bình của mọi hành khách từ tệp chuyến đi MATSim.
        :return: Độ dài chuyến đi trung bình của hành khách (tính bằng mét).
        """
        trip_file = self._config["files"]["data"]["total_dist"]

        cols = ["total_distance"]
        dtype = {
            "total_distance": float,
        }

        df = read_dataframe(trip_file, self._debug, usecols=cols, dtype=dtype)
        avg_dist = df[df["total_distance"] > 0]["total_distance"].mean()
        if self._debug:
            print(f"Average passenger trip length: {avg_dist} meters")
        return avg_dist

    def process_vehicle_flow(self) -> pd.DataFrame:
        veh_flow_file = self._config["files"]["data"]["veh_flow"]

        cols = ["vehicle_id", "link_id", "hour", "line_id", "duration", "travel_distance"]
        dtype = {
            "vehicle_id": str,
            "link_id": str,
            "line_id": str,
            "duration": float,
            "travel_distance": float,
        }

        df = read_dataframe(veh_flow_file, self._debug, usecols=cols, dtype=dtype)
        # Lọc chỉ giữ lại các xe buýt (vehicle_id có trong danh sách bus_vehicle_ids)
        df_filtered = df[df["vehicle_id"].isin(self._bus_vehicle_ids)]

        # Tính toán thống kê lưu lượng và tốc độ trung bình của toàn dòng phương tiện theo link
        df_hour_stat = df.groupby(["link_id", "hour"]).agg(
            veh_count=("vehicle_id", "count"),
            sum_duration=("duration", "sum"),
            sum_travel_dist=("travel_distance", "sum"),
        )
        df_stat = df_hour_stat.groupby(["link_id"]).agg(
            veh_flow=("veh_count", "mean"),
            total_duration=("sum_duration", "sum"),
            total_travel_dist=("sum_travel_dist", "sum"),
        )
        df_stat["avg_speed"] = df_stat["total_travel_dist"] / df_stat["total_duration"]
        df_stat = df_stat.drop(columns=["total_duration", "total_travel_dist"])
        if self._debug:
            dataframe_to_file(df_stat, "data/test/csv/veh_flow_debug.csv", self._debug, index_label="link_id")

        # Tính toán thống kê vận tốc trung bình của xe buýt theo link và line
        df_bus_line_spd = df_filtered.groupby(["link_id", "line_id"]).agg(
            total_duration=("duration", "sum"),
            total_travel_dist=("travel_distance", "sum"),
        )
        df_bus_line_spd["avg_bus_speed"] = df_bus_line_spd["total_travel_dist"] / df_bus_line_spd["total_duration"]
        if self._debug:
            dataframe_to_file(df_bus_line_spd, "data/test/csv/bus_line_speed_debug.csv", self._debug, index_label=["link_id", "line_id"])

        # Tính toán thống kê vận tốc trung bình của xe buýt theo link
        df_bus_link_spd = df_filtered.groupby(["link_id"]).agg(
            total_duration=("duration", "sum"),
            total_travel_dist=("travel_distance", "sum"),
        )
        df_bus_link_spd["avg_bus_speed"] = df_bus_link_spd["total_travel_dist"] / df_bus_link_spd["total_duration"]
        if self._debug:
            dataframe_to_file(df_bus_link_spd, "data/test/csv/bus_link_speed_debug.csv", self._debug, index_label="link_id")

        bus_lines_map = (
            df_bus_line_spd.groupby(["link_id"])
            .apply(lambda x: dict(zip(x.index.get_level_values('line_id'), x["avg_bus_speed"])))
            .to_dict()
        )
        if self._debug:
            json_writer(bus_lines_map, "data/test/json/bus_lines_speed_debug.json")
        bus_links_map = df_bus_link_spd["avg_bus_speed"].to_dict()

        return pd.DataFrame.from_dict({
            row.Index: {
                "veh_flow": row.veh_flow,
                "avg_speed": row.avg_speed,
                "avg_bus_speed_per_line": bus_lines_map.get(row.Index, {}),
                "avg_bus_speed": bus_links_map.get(row.Index, 0.0),
            }
            for row in df_stat.itertuples()
        })

    def process_avg_ewt(self) -> pd.DataFrame:
        """
        Tính toán EWT trọng số với phương án dự phòng là trung bình cộng đơn giản.
        Nếu tổng lượng hành khách lên xe < ngưỡng (boarding_threshold), sử dụng trung bình cộng đơn giản thay vì trung bình trọng số.
        """
        wt_file = self._config["files"]["data"]["wait_time"]
        link_data_file = self._config["files"]["metadata"]["link_data"]
        transit_schedule_file = self._config["files"]["inp"]["transit"]
        boarding_threshold = int(self._config["scoring"]["wait_ride"]["boarding_threshold"])

        # Hàm tổng hợp EWT bằng trung bình trọng số, dự phòng về trung bình cộng nếu lượng lên xe < ngưỡng
        def aggregate_ewt(data: pd.DataFrame, group_by: list) -> pd.DataFrame:
            """Tổng hợp EWT bằng trung bình trọng số, dự phòng về trung bình cộng nếu lượng lên xe < ngưỡng."""
            result = data.groupby(group_by).agg(
                sum_weighted_deviation=("weighted_deviation", "sum"),
                sum_weight=("boarding", "sum"),
                delay_mean=("schedule_deviation", "mean")
            )
            result["ewt"] = result.apply(
                lambda row: row["sum_weighted_deviation"] / row["sum_weight"]
                if row["sum_weight"] >= boarding_threshold else row["delay_mean"],
                axis=1
            )
            return result

        # --- Logic chính ---
        cols = ["link_id", "line_id", "schedule_deviation", "boarding"]
        dtype = {"link_id": str, "line_id": str, "schedule_deviation": float, "boarding": int}

        df = read_dataframe(wt_file, self._debug, usecols=cols, dtype=dtype)

        # Áp dụng hiệu chỉnh sai số headway
        df["line_headway"] = df["line_id"].map(self.bus_line_headway)
        tolerance_threshold = -self.hw_tolerance * 60.0
        mask = df["schedule_deviation"] < tolerance_threshold
        df.loc[mask, "schedule_deviation"] = df.loc[mask, "line_headway"]
        df = df.drop(columns=["line_headway"])

        # Tính toán độ lệch trọng số
        df["weighted_deviation"] = df["schedule_deviation"] * df["boarding"]
        if self._debug:
            dataframe_to_file(df, "data/test/csv/ewt_weighted_deviation_debug.csv", self._debug, index_label=None)

        # Tính toán EWT với tổng hợp trọng số/dự phòng
        df_ewt_per_line = aggregate_ewt(df, ["link_id", "line_id"])
        df_ewt_per_link = aggregate_ewt(df, ["link_id"])

        if self._debug:
            dataframe_to_file(df_ewt_per_link, "data/test/csv/ewt_per_link_debug.csv", self._debug, index_label="link_id")
            dataframe_to_file(df_ewt_per_line, "data/test/csv/ewt_per_line_debug.csv", self._debug, index_label=["link_id", "line_id"])

        # Xây dựng mappings EWT
        ewt_lines_map = (
            df_ewt_per_line.groupby(["link_id"])
            .apply(lambda x: dict(zip(x.index.get_level_values('line_id'), x["ewt"])))
            .to_dict()
        )
        ewt_links_map = df_ewt_per_link["ewt"].to_dict()

        # --- Lan truyền EWT cho các link không có điểm dừng ---
        # Sử dụng delay propagation thay vì nội suy trung bình
        link_data_df = read_dataframe(link_data_file, self._debug, dtype={"link_id": str})
        all_bus_links = set(link_data_df[link_data_df["bus_frequency"] > 0]["link_id"].tolist())
        link_length_map = dict(zip(link_data_df["link_id"], link_data_df["length"]))
        stop_links = set(ewt_links_map.keys())

        transit_tree = etree.parse(transit_schedule_file)

        # Tải dữ liệu veh_flow để tính thời gian di chuyển thực tế
        veh_flow_file = self._config["files"]["data"]["veh_flow"]
        veh_flow_cols = ["vehicle_id", "link_id", "line_id", "slice_start", "duration", "travel_distance"]
        veh_flow_dtype = {
            "vehicle_id": str,
            "link_id": str,
            "line_id": str,
            "slice_start": float,
            "duration": float,
            "travel_distance": float,
        }
        veh_flow_df = read_dataframe(veh_flow_file, self._debug, usecols=veh_flow_cols, dtype=veh_flow_dtype)

        # Tính thời gian di chuyển thực tế và tốc độ tham chiếu
        avg_travel_time, reference_speed = self.__compute_actual_travel_times(veh_flow_df)

        # Lan truyền EWT dọc theo các tuyến
        self.__propagate_ewt_along_routes(
            transit_tree=transit_tree,
            ewt_links_map=ewt_links_map,
            ewt_lines_map=ewt_lines_map,
            stop_links=stop_links,
            all_bus_links=all_bus_links,
            link_length_map=link_length_map,
            avg_travel_time=avg_travel_time,
            reference_speed=reference_speed,
        )

        if self._debug:
            json_writer(ewt_links_map, "data/test/json/ewt_links_map_debug.json")
            json_writer(ewt_lines_map, "data/test/json/ewt_lines_map_debug.json")

        return pd.DataFrame.from_dict({
            link_id: {
                "ewt_per_line": ewt_lines_map.get(link_id, {}),
                "ewt": ewt_links_map.get(link_id, 0.0),
            }
            for link_id in ewt_links_map.keys()
        })

    def process_avg_load_factor(self) -> pd.DataFrame:
        """
        Tính toán hệ số tải trung bình trên mỗi link với phương pháp dự phòng.
        Chính: Trung bình trọng số theo pax-seconds của hệ số tải tức thời.
        Dự phòng (khi tổng entry_load < ngưỡng): Trung bình trọng số thời gian theo chuyến, sau đó trung bình theo số lượng xe.
        """
        lf_file = self._config["files"]["data"]["load_history"]
        passenger_threshold = int(self._config["scoring"]["wait_ride"]["total_load_threshold"])

        cols = ["vehicle_id", "link_id", "line_id", "duration", "passenger_load", "pax_seconds", "instant_load_factor"]
        dtype = {
            "vehicle_id": str,
            "link_id": str,
            "line_id": str,
            "duration": float,
            "passenger_load": int,
            "pax_seconds": float,
            "instant_load_factor": float,
        }

        df = read_dataframe(lf_file, self._debug, usecols=cols, dtype=dtype)

        # Tính hệ số tải trọng số cho phương pháp chính
        df["weighted_lf"] = df["instant_load_factor"] * df["pax_seconds"]
        # Tính hệ số tải trọng số thời gian cho phương pháp dự phòng
        df["time_weighted_lf"] = df["instant_load_factor"] * df["duration"]

        if self._debug:
            dataframe_to_file(df, "data/test/csv/load_factor_records_debug.csv", self._debug, index=False)

        # Hàm tổng hợp thống kê cơ bản theo nhóm
        def aggregate_stats(group_by: list) -> pd.DataFrame:
            """Tổng hợp thống kê cơ bản theo nhóm."""
            return df.groupby(group_by).agg(
                total_pax=("passenger_load", "sum"),
                sum_weighted_lf=("weighted_lf", "sum"),
                sum_pax_seconds=("pax_seconds", "sum"),
            )

        # Hàm tính hệ số tải với phương pháp chính (trọng số theo pax-seconds)
        def compute_primary_lf(stats_df: pd.DataFrame) -> pd.DataFrame:
            """Tính hệ số tải với phương pháp chính (trọng số theo pax-seconds)."""
            primary = stats_df[stats_df["total_pax"] >= passenger_threshold].copy()
            primary["avg_load_factor"] = primary["sum_weighted_lf"] / primary["sum_pax_seconds"]
            return primary[["avg_load_factor"]]

        # Hàm tính hệ số tải với phương pháp dự phòng (trung bình đơn giản)
        def compute_fallback_lf(stats_df: pd.DataFrame, group_by: list) -> pd.DataFrame:
            """Tính hệ số tải với phương pháp dự phòng (trung bình đơn giản)."""
            fallback_idx = stats_df[stats_df["total_pax"] < passenger_threshold].index
            if len(fallback_idx) == 0:
                return pd.DataFrame(columns=["avg_load_factor"])

            fallback_slices = df.set_index(group_by).loc[
                df.set_index(group_by).index.isin(fallback_idx)
            ].reset_index()

            if len(fallback_slices) == 0:
                return pd.DataFrame(columns=["avg_load_factor"])

            return fallback_slices.groupby(group_by).agg(
                avg_load_factor=("instant_load_factor", "mean")
            )

        # --- Tổng hợp theo [link, line] ---
        line_group = ["link_id", "line_id"]
        df_line_stats = aggregate_stats(line_group)
        df_bus_line_load = pd.concat([
            compute_primary_lf(df_line_stats),
            compute_fallback_lf(df_line_stats, line_group)
        ])

        # --- Tổng hợp theo [link] ---
        link_group = ["link_id"]
        df_link_stats = aggregate_stats(link_group)
        df_bus_link_load = pd.concat([
            compute_primary_lf(df_link_stats),
            compute_fallback_lf(df_link_stats, link_group)
        ])

        if self._debug:
            dataframe_to_file(df_bus_line_load, "data/test/csv/bus_line_load_debug.csv", self._debug, index_label=["link_id", "line_id"])
            dataframe_to_file(df_bus_link_load, "data/test/csv/bus_link_load_debug.csv", self._debug, index_label="link_id")

        # Xây dựng mapping theo từng line
        bus_lines_load = (
            df_bus_line_load.groupby(["link_id"])
            .apply(lambda x: dict(zip(x.index.get_level_values('line_id'), x["avg_load_factor"])))
            .to_dict()
        )

        if self._debug:
            json_writer(bus_lines_load, "data/test/json/bus_lines_load_debug.json")

        return pd.DataFrame.from_dict({
            row.Index: {
                "avg_load_factor_per_line": bus_lines_load.get(row.Index, {}),
                "avg_load_factor": row.avg_load_factor,
            }
            for row in df_bus_link_load.itertuples()
        })

    def process_matsim_data(self) -> None:
        # Trích xuất dữ liệu link
        def load_link_data() -> pd.DataFrame:
            link_data = self._config["files"]["metadata"]["link_data"]
            cols = ["link_id", "length", "bus_frequency"]
            dtype = {
                "link_id": str,
                "length": float,
                "bus_frequency": float,
            }
            return read_dataframe(link_data, self._debug, usecols=cols, dtype=dtype, index_col=0)

        # Tính toán chỉ số độ dài chuyến đi trung bình
        avg_trip = self.process_avg_psg_trip_len()
        json_writer({
            "avg_passenger_trip_length": avg_trip
        }, self._config["files"]["data"]["avg_trip_length"])

        # Tính toán chỉ số thời gian chờ trung bình
        avg_ewt = self.process_avg_ewt()

        # Tính toán chỉ số lưu lượng phương tiện
        veh_flow = self.process_vehicle_flow()

        # Tính toán chỉ số hệ số tải
        avg_lf = self.process_avg_load_factor()

        # Xây dựng dữ liệu LOS
        df_link = load_link_data()
        merged = [avg_ewt, veh_flow, avg_lf]
        los_data = merged[0].T
        for df in merged[1:]:
            los_data = los_data.join(df.T, how="outer")
        los_data = los_data.join(df_link, how="outer")

        # Lưu dữ liệu thô cho tính toán LOS
        merged_los_file = self._config["files"]["los_data"]["merged"]
        dataframe_to_file(los_data, merged_los_file, self._debug, index_label="link_id")

        # Lưu dữ liệu đã lọc bỏ các link không có xe buýt đi qua cho tính toán LOS
        # TODO: Kiểm tra lại logic lọc dữ liệu cho tính toán LOS
        filtered_los_data = los_data[los_data["bus_frequency"] > 0].copy()
        filtered_los_file = self._config["files"]["los_data"]["filtered"]
        dataframe_to_file(filtered_los_data, filtered_los_file, self._debug, index_label="link_id")

        # Lưu dữ liệu ngoại lai bị lọc bỏ 
        outlier = los_data[los_data["bus_frequency"] <= 0].copy()
        outlier_file = self._config["files"]["los_data"]["outlier"]
        dataframe_to_file(outlier, outlier_file, self._debug, index_label="link_id")
