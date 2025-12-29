import gzip
import pandas as pd

from lxml import etree
from scoring.utility import (
    yaml_parser,
    json_writer,
    dataframe_to_file,
    StreamingMultiWriter,
)


class MATSimExtractor:
    def __init__(self, config: str):
        self._config = yaml_parser(config)
        self._debug = self._config["mode"] == "debug"
        self.event = self._config["files"]["out"]["events"]

        self._link_data_dict = {}
        self.__make_link_data_dict()

        self._bus_line_dict = {}
        self._line_headway_dict = {}
        self.__make_bus_line_dict()

        # Create blacklist of non-bus vehicles
        self.vehicle_blacklist = set()
        self.__create_vehicle_blacklist()

        self.__output_metadata()

        if self._debug:
            json_writer(self._bus_line_dict, "data/test/json/bus_line_dict.json")
            json_writer(
                self._line_headway_dict, "data/test/json/line_headway_dict.json"
            )
            json_writer(self._link_data_dict, "data/test/json/link_data_dict.json")

        # TODO: điều chỉnh động dựa trên loại xe buýt cho các phiên bản sau
        seating_cap = int(self._config["bus"]["seating"])
        standing_cap = int(self._config["bus"]["standing"])
        cap_headroom = float(self._config["bus"]["cap_headroom"])
        self.bus_cap = int(seating_cap + standing_cap * cap_headroom)

    def __make_link_data_dict(self):
        """Tạo 1 dict gồm các cạnh và thông tin của chúng (độ dài, tần suất bus, v.v.)"""

        for _, elem in etree.iterparse(
            self._config["files"]["inp"]["net"], events=("end",), tag="link"
        ):
            e = elem.attrib
            if e["from"] != e["to"] and (
                "pt" in e["modes"] or "car" in e["modes"]
            ):  # Loại bỏ các link loopp
                self._link_data_dict[e["id"]] = {
                    "len": float(e["length"]),
                    "bus_freq": 0.0,  # Will be populated by __make_bus_line_dict
                }
            e.clear()

    def __make_bus_line_dict(self):
        """Tạo 1 dict gồm các tuyến xe bus và ID xe buýt, đồng thời tính toán headway theo lịch trình"""

        def parse_departure_times(dep_times_str: list[str]) -> list[int]:
            """Chuyển đổi chuỗi HH:MM:SS thành giây duy nhất được sắp xếp"""
            dep_times_sec = []
            for t in dep_times_str:
                parts = t.split(":")
                seconds = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
                dep_times_sec.append(seconds)

            return sorted(set(dep_times_sec))

        transit_tree = etree.parse(self._config["files"]["inp"]["transit"])

        # Xây dựng mapping stop -> link từ định nghĩa stopFacility
        stop_to_link = {}
        for stop in transit_tree.findall(".//stopFacility"):
            stop_id = stop.get("id")
            link_ref = stop.get("linkRefId")
            if stop_id and link_ref:
                stop_to_link[stop_id] = link_ref

        for line in transit_tree.findall("transitLine"):
            # Filter: only process lines with matching transport mode from config
            allowed_modes = set(self._config["matsim"].get("bus_transport_modes", ["bus", "pt"]))
            transport_modes = set(line.xpath(".//transitRoute/transportMode/text()"))
            if not transport_modes or not transport_modes & allowed_modes:
                continue

            line_id = line.get("id")

            # Trích xuất ID xe và ánh xạ tới tuyến
            veh_ids = line.xpath(".//departure/@vehicleRefId")
            for veh_id in veh_ids:
                self._bus_line_dict[veh_id] = line_id

            # Trích xuất thời gian khởi hành và tính toán headway theo lịch trình
            departure_times_str = line.xpath(".//departure/@departureTime")
            if len(departure_times_str) >= 2:
                dep_times_s = parse_departure_times(departure_times_str)
                headways = [
                    dep_times_s[i + 1] - dep_times_s[i]
                    for i in range(len(dep_times_s) - 1)
                ]

                # Headway trung bình tính bằng giây
                self._line_headway_dict[line_id] = sum(headways) / len(headways)
            else:
                # Chỉ có một chuyến khởi hành, không thể tính headway
                self._line_headway_dict[line_id] = None

            # Tính tần suất xe buýt theo lịch trình trên mỗi link cho từng lộ trình
            for route in line.findall("transitRoute"):
                # Lấy tất cả các link trong chuỗi lộ trình (để nội suy)
                route_links = route.xpath(".//route/link/@refId")

                # Lấy ID trạm dừng từ routeProfile (chỉ tính các link có trạm dừng)
                stop_ids = route.xpath(".//routeProfile/stop/@refId")

                # Xây dựng tập hợp các link có trạm dừng
                stop_link_ids = set()
                for stop_id in stop_ids:
                    link_id = stop_to_link.get(stop_id)
                    if link_id:
                        stop_link_ids.add(link_id)

                # Lấy thời gian khởi hành cho lộ trình cụ thể này
                route_deps_str = route.xpath(".//departure/@departureTime")
                num_departures = (
                    len(route_deps_str) - 1
                )  # Loại trừ khởi hành ban đầu tại thời gian 0

                # Tính tần suất: số chuyến khởi hành mỗi giờ
                if num_departures > 0:
                    dep_times = parse_departure_times(route_deps_str)

                    # Giờ hoạt động dựa trên chuyến khởi hành đầu/cuối
                    operating_hours = (dep_times[-1] - dep_times[0]) / 3600.0
                    if operating_hours > 0:
                        frequency = num_departures / operating_hours
                    else:
                        frequency = num_departures  # Khởi hành đơn lẻ

                    # Lượt 1: Thêm tần suất vào các link có trạm dừng xe buýt
                    for link_id in stop_link_ids:
                        if link_id in self._link_data_dict:
                            self._link_data_dict[link_id]["bus_freq"] += frequency

                    # Lượt 2: Nội suy cho các link đi qua (không dừng)
                    # Tìm link có trạm dừng gần nhất trước và sau mỗi link không dừng
                    for i, link_id in enumerate(route_links):
                        if (
                            link_id not in stop_link_ids
                            and link_id in self._link_data_dict
                        ):
                            # Tìm trạm dừng gần nhất phía trước
                            prev_freq = None
                            for j in range(i - 1, -1, -1):
                                if route_links[j] in stop_link_ids:
                                    prev_freq = frequency
                                    break

                            # Tìm trạm dừng gần nhất phía sau
                            next_freq = None
                            for j in range(i + 1, len(route_links)):
                                if route_links[j] in stop_link_ids:
                                    next_freq = frequency
                                    break

                            # Nội suy: trung bình của các trạm lân cận có sẵn
                            if prev_freq is not None and next_freq is not None:
                                interp_freq = (prev_freq + next_freq) / 2.0
                            elif prev_freq is not None:
                                interp_freq = prev_freq
                            elif next_freq is not None:
                                interp_freq = next_freq
                            else:
                                interp_freq = 0.0

                            self._link_data_dict[link_id]["bus_freq"] += interp_freq

    def __create_vehicle_blacklist(self):
        """
        Tạo blacklist các xe không phải bus từ file transit vehicles.
        Blacklist bao gồm các xe có vehicle type KHÔNG bắt đầu với bus_type_prefix.
        Ví dụ: tram, train, etc. sẽ bị loại trừ khỏi việc theo dõi.
        """
        vehicle_file = self._config["files"]["inp"]["transit_vehicles"]
        bus_type_prefix = self._config["matsim"]["bus_type_prefix"]

        vehicle_tree = etree.parse(vehicle_file)

        # Lấy tất cả vehicle types KHÔNG chứa prefix trong ID (non-bus types)
        non_bus_types = set()
        for veh_type in vehicle_tree.xpath(".//*[local-name()='vehicleType']"):
            type_id = veh_type.get("id")
            if type_id and not type_id.lower().startswith(bus_type_prefix.lower()):
                non_bus_types.add(type_id)

        # Lọc các xe có type thuộc non_bus_types và thêm vào blacklist
        for vehicle in vehicle_tree.xpath(".//*[local-name()='vehicle']"):
            veh_id = vehicle.get("id")
            veh_type = vehicle.get("type")
            if veh_type in non_bus_types:
                self.vehicle_blacklist.add(veh_id)

        if self._debug:
            print(
                f"Created blacklist with {len(self.vehicle_blacklist)} non-bus vehicles"
            )
            print(f"Non-bus vehicle types: {non_bus_types}")

    def __output_metadata(self):
        """
        Xuất thông tin metadata về headway và tần suất bus ra file
        """

        # Headway metadata
        headway_data = [
            {"line_id": line_id, "scheduled_headway": headway}
            for line_id, headway in self._line_headway_dict.items()
        ]
        headway_df = pd.DataFrame(headway_data)
        dataframe_to_file(
            headway_df,
            self._config["files"]["metadata"]["bus_headway"],
            self._debug,
            index=False,
        )

        # Link data metadata
        link_data = [
            {
                "link_id": link_id,
                "length": data["len"],
                "bus_frequency": data["bus_freq"],
            }
            for link_id, data in self._link_data_dict.items()
        ]
        link_df = pd.DataFrame(link_data)
        dataframe_to_file(
            link_df,
            self._config["files"]["metadata"]["link_data"],
            self._debug,
            index=False,
        )

        # Bus vehicle IDs metadata
        self.__extract_bus_vehicle_ids()

    def __extract_bus_vehicle_ids(self):
        """
        Trích xuất danh sách ID xe buýt từ file định nghĩa xe transit.
        Lọc xe theo loại vehicle type có prefix từ config.

        Returns:
            DataFrame chứa vehicle_id và vehicle_type của các xe buýt
        """
        vehicle_file = self._config["files"]["inp"]["transit_vehicles"]
        output_file = self._config["files"]["metadata"]["bus_vehicles"]
        bus_type_prefix = self._config["matsim"]["bus_type_prefix"]

        vehicle_tree = etree.parse(vehicle_file)

        # Lấy tất cả vehicle types có chứa prefix trong ID
        bus_types = set()
        for veh_type in vehicle_tree.xpath(".//*[local-name()='vehicleType']"):
            type_id = veh_type.get("id")
            if type_id and type_id.lower().startswith(bus_type_prefix.lower()):
                bus_types.add(type_id)

        # Lọc các xe có type thuộc bus_types
        bus_vehicles = []
        all_vehicles = vehicle_tree.xpath(".//*[local-name()='vehicle']")
        for vehicle in all_vehicles:
            veh_id = vehicle.get("id")
            veh_type = vehicle.get("type")
            if veh_type in bus_types:
                bus_vehicles.append({"vehicle_id": veh_id, "vehicle_type": veh_type})

        df = pd.DataFrame(bus_vehicles)
        dataframe_to_file(df, output_file, self._debug, index=False)

        if self._debug:
            print(f"Extracted {len(bus_vehicles)} bus vehicles from {vehicle_file}")

    def __extract_all(self, writer: StreamingMultiWriter):
        """
        Trích xuất tất cả dữ liệu trong một lần đọc file events.xml.gz.
        Sử dụng dispatch table để xử lý events hiệu quả hơn.

        Args:
            writer: StreamingMultiWriter để ghi kết quả trực tiếp
        """

        # ====================================================================
        # HELPER FUNCTIONS
        # ====================================================================
        def generate_time_sliced_records(vehicle_id, link_id, line_id, enter_time, exit_time, link_len):
            """
            Tạo các bản ghi lưu lượng được chia theo từng khung giờ.
            Nếu không hợp lệ (duration <= 1s hoặc link_len <= 0), không tạo bản ghi.
            """
            travel_duration = exit_time - enter_time
            if link_len <= 0 or travel_duration < 1.0:
                return

            avg_speed = link_len / travel_duration
            time_ptr = enter_time

            while time_ptr < exit_time:
                curr_hour = int(time_ptr // 3600)
                hour_end_time = (curr_hour + 1) * 3600
                slice_end = min(exit_time, hour_end_time)
                duration = slice_end - time_ptr

                if duration > 0:
                    slice_dist = avg_speed * duration
                    writer.write("veh_flow", {
                        "vehicle_id": vehicle_id,
                        "link_id": link_id,
                        "line_id": line_id,
                        "hour": curr_hour,
                        "slice_start": time_ptr,
                        "slice_end": slice_end,
                        "duration": duration,
                        "travel_distance": slice_dist,
                        "avg_speed": avg_speed,
                    })

                time_ptr = slice_end

        def write_bus_load_record(vehicle_id, vs, exit_time):
            """Ghi bản ghi tải trọng xe bus cho một link."""
            if vs["current_link"] is None or vs["link_enter_time"] is None:
                return

            duration = exit_time - vs["link_enter_time"]
            load = vs["entry_load"]

            if duration > 0:
                writer.write("load_history", {
                    "vehicle_id": vehicle_id,
                    "link_id": vs["current_link"],
                    "line_id": self._bus_line_dict.get(vehicle_id, None),
                    "duration": duration,
                    "passenger_load": load,
                    "pax_seconds": load * duration,
                    "plan_cap": self.bus_cap,
                    "plan_cap_seconds": self.bus_cap * duration,
                    "instant_load_factor": load / self.bus_cap if self.bus_cap > 0 else 0.0,
                })

        # ====================================================================
        # STATE DICTIONARIES
        # ====================================================================
        traffic_flow_state = {}
        bus_load_state = {}
        trip_dist_state = {}
        bus_delay_state = {}

        # ====================================================================
        # HANDLER FUNCTIONS
        # Each handler encapsulates its own filtering conditions
        # ====================================================================

        # --- Traffic Flow Handlers ---
        def handle_traffic_flow_enter(e):
            """Handle entered link / vehicle enters traffic for traffic flow."""
            vehicle_id = e["vehicle"]
            if vehicle_id not in self.vehicle_blacklist:
                link_data = self._link_data_dict.get(e["link"], {})
                link_length = link_data.get("len", 0.0)
                if link_length > 0:
                    traffic_flow_state[vehicle_id] = {
                        "link": e["link"],
                        "enter_time": e["time"],
                        "link_len": link_length,
                    }

        def handle_traffic_flow_leave(e):
            """Handle vehicle leaves traffic for traffic flow."""
            vehicle_id = e["vehicle"]
            if vehicle_id in traffic_flow_state:
                vs = traffic_flow_state[vehicle_id]
                generate_time_sliced_records(
                    vehicle_id=vehicle_id,
                    link_id=vs["link"],
                    line_id=self._bus_line_dict.get(vehicle_id, None),
                    enter_time=float(vs["enter_time"]),
                    exit_time=float(e["time"]),
                    link_len=vs["link_len"],
                )
                del traffic_flow_state[vehicle_id]

        # --- Bus Load History Handlers ---
        def handle_bus_load_start(e):
            """Handle TransitDriverStarts for bus load."""
            vehicle_id = e["vehicleId"]
            if vehicle_id not in self.vehicle_blacklist:
                bus_load_state[vehicle_id] = {
                    "current_rider": 0,
                    "current_link": None,
                    "link_enter_time": None,
                    "entry_load": 0,
                }

        def handle_bus_load_enter(e):
            """Handle entered link for bus load."""
            vehicle_id = e["vehicle"]
            if vehicle_id in bus_load_state:
                vs = bus_load_state[vehicle_id]
                vs["current_link"] = e["link"]
                vs["link_enter_time"] = float(e["time"])
                vs["entry_load"] = vs["current_rider"]

        def handle_bus_load_person_enter(e):
            """Handle PersonEntersVehicle for bus load."""
            if not e["person"].startswith("pt_"):
                vehicle_id = e["vehicle"]
                if vehicle_id in bus_load_state:
                    bus_load_state[vehicle_id]["current_rider"] += 1

        def handle_bus_load_person_leave(e):
            """Handle PersonLeavesVehicle for bus load."""
            if not e["person"].startswith("pt_"):
                vehicle_id = e["vehicle"]
                if vehicle_id in bus_load_state:
                    bus_load_state[vehicle_id]["current_rider"] -= 1

        def handle_bus_load_exit(e):
            """Handle left link for bus load."""
            vehicle_id = e["vehicle"]
            if vehicle_id in bus_load_state:
                vs = bus_load_state[vehicle_id]
                write_bus_load_record(vehicle_id, vs, float(e["time"]))
                vs["current_link"] = None
                vs["link_enter_time"] = None

        def handle_bus_load_leave(e):
            """Handle vehicle leaves traffic for bus load."""
            if "person" in e and e["person"].startswith("pt_"):
                vehicle_id = e["vehicle"]
                if vehicle_id in bus_load_state:
                    vs = bus_load_state[vehicle_id]
                    write_bus_load_record(vehicle_id, vs, float(e["time"]))
                    del bus_load_state[vehicle_id]

        # --- Trip Total Distance Handlers ---
        def handle_trip_dist_start(e):
            """Handle vehicle enters traffic for trip distance."""
            vehicle_id = e["vehicle"]
            if vehicle_id not in self.vehicle_blacklist:
                trip_dist_state[vehicle_id] = {
                    "trip_start_time": float(e["time"]),
                    "total_distance": 0.0,
                    "current_link_enter_time": None,
                    "current_link_len": 0.0,
                }

        def handle_trip_dist_enter(e):
            """Handle entered link for trip distance."""
            vehicle_id = e["vehicle"]
            if vehicle_id in trip_dist_state:
                link_data = self._link_data_dict.get(e["link"], {})
                link_length = link_data.get("len", 0.0)
                trip_dist_state[vehicle_id]["current_link_enter_time"] = float(e["time"])
                trip_dist_state[vehicle_id]["current_link_len"] = link_length

        def handle_trip_dist_exit(e):
            """Handle left link for trip distance."""
            vehicle_id = e["vehicle"]
            if vehicle_id in trip_dist_state:
                vs = trip_dist_state[vehicle_id]
                if vs["current_link_enter_time"] is not None:
                    exit_time = float(e["time"])
                    enter_time = vs["current_link_enter_time"]
                    duration = exit_time - enter_time
                    link_len = vs["current_link_len"]

                    if duration > 1.0 and link_len > 0:
                        vs["total_distance"] += link_len

                vs["current_link_enter_time"] = None

        def handle_trip_dist_leave(e):
            """Handle vehicle leaves traffic for trip distance."""
            vehicle_id = e["vehicle"]
            if vehicle_id in trip_dist_state:
                vs = trip_dist_state[vehicle_id]
                end_time = float(e["time"])
                duration = end_time - vs["trip_start_time"]

                writer.write("total_dist", {
                    "vehicle_id": vehicle_id,
                    "start_time": vs["trip_start_time"],
                    "end_time": end_time,
                    "duration": duration,
                    "total_distance": vs["total_distance"],
                    "avg_speed_mps": vs["total_distance"] / duration if duration > 0 else 0,
                })
                del trip_dist_state[vehicle_id]

        # --- Bus Delay Handlers ---
        def handle_bus_delay_start(e):
            """Handle TransitDriverStarts for bus delay."""
            vehicle_id = e["vehicleId"]
            if vehicle_id not in self.vehicle_blacklist:
                bus_delay_state[vehicle_id] = {
                    "current_link": None,
                    "current_stop": None,
                    "current_delay": 0,
                    "arrival_time": 0,
                    "pending_boarding": 0,
                    "pending_alighting": 0,
                    "total_boarding": 0,
                    "total_alighting": 0,
                }

        def handle_bus_delay_enter(e):
            """Handle entered link for bus delay."""
            vehicle_id = e["vehicle"]
            if vehicle_id in bus_delay_state:
                bus_delay_state[vehicle_id]["current_link"] = e["link"]

        def handle_bus_delay_arrive(e):
            """Handle VehicleArrivesAtFacility for bus delay."""
            vehicle_id = e["vehicle"]
            if vehicle_id in bus_delay_state:
                vs = bus_delay_state[vehicle_id]
                if vs["current_link"] is not None:
                    vs["current_stop"] = e["facility"]
                    vs["current_delay"] = float(e.get("delay", 0))
                    vs["arrival_time"] = float(e["time"])

        def handle_bus_delay_person_enter(e):
            """Handle PersonEntersVehicle for bus delay."""
            if not e["person"].startswith("pt_"):
                vehicle_id = e["vehicle"]
                if vehicle_id in bus_delay_state:
                    bus_delay_state[vehicle_id]["pending_boarding"] += 1
                    bus_delay_state[vehicle_id]["total_boarding"] += 1

        def handle_bus_delay_person_leave(e):
            """Handle PersonLeavesVehicle for bus delay."""
            if not e["person"].startswith("pt_"):
                vehicle_id = e["vehicle"]
                if vehicle_id in bus_delay_state:
                    bus_delay_state[vehicle_id]["pending_alighting"] += 1
                    bus_delay_state[vehicle_id]["total_alighting"] += 1

        def handle_bus_delay_depart(e):
            """Handle VehicleDepartsAtFacility for bus delay."""
            vehicle_id = e["vehicle"]
            if vehicle_id in bus_delay_state:
                vs = bus_delay_state[vehicle_id]
                if vs["current_stop"] is not None:
                    current_hour = int(vs["arrival_time"] // 3600)
                    writer.write("wait_time", {
                        "vehicle_id": vehicle_id,
                        "stop_id": vs["current_stop"],
                        "link_id": vs["current_link"],
                        "line_id": self._bus_line_dict.get(vehicle_id, None),
                        "hour": current_hour,
                        "timestamp": vs["arrival_time"],
                        "schedule_deviation": vs["current_delay"],
                        "boarding": vs["pending_boarding"],
                        "alighting": vs["pending_alighting"],
                    })
                    vs["current_stop"] = None
                    vs["pending_boarding"] = 0
                    vs["pending_alighting"] = 0

        def handle_bus_delay_leave(e):
            """Handle vehicle leaves traffic for bus delay."""
            if "person" in e and e["person"].startswith("pt_"):
                vehicle_id = e["vehicle"]
                if vehicle_id in bus_delay_state:
                    vs = bus_delay_state[vehicle_id]
                    assert vs["total_boarding"] == vs["total_alighting"], (
                        f"Bus {vehicle_id} ended with unbalanced passengers: "
                        f"boarding={vs['total_boarding']}, alighting={vs['total_alighting']}"
                    )
                    del bus_delay_state[vehicle_id]

        # ====================================================================
        # DISPATCH TABLE
        # Maps event_type -> list of handlers to call
        # ====================================================================
        event_handlers = {
            # Enter events
            "entered link": [
                handle_traffic_flow_enter,
                handle_bus_load_enter,
                handle_trip_dist_enter,
                handle_bus_delay_enter,
            ],
            "vehicle enters traffic": [
                handle_traffic_flow_enter,
                handle_trip_dist_start,
            ],

            # Exit events
            "left link": [
                handle_traffic_flow_leave,
                handle_bus_load_exit,
                handle_trip_dist_exit,
            ],
            "vehicle leaves traffic": [
                handle_traffic_flow_leave,
                handle_bus_load_leave,
                handle_trip_dist_leave,
                handle_bus_delay_leave,
            ],

            # Transit-specific events
            "TransitDriverStarts": [
                handle_bus_load_start,
                handle_bus_delay_start,
            ],

            # Person events
            "PersonEntersVehicle": [
                handle_bus_load_person_enter,
                handle_bus_delay_person_enter,
            ],
            "PersonLeavesVehicle": [
                handle_bus_load_person_leave,
                handle_bus_delay_person_leave,
            ],

            # Facility events (bus delay only)
            "VehicleArrivesAtFacility": [handle_bus_delay_arrive],
            "VehicleDepartsAtFacility": [handle_bus_delay_depart],
        }

        # ====================================================================
        # MAIN EVENT LOOP - O(1) lookup + dispatch
        # ====================================================================
        with gzip.open(self.event, "rb") as f:
            for _, elem in etree.iterparse(f, events=("end",), tag="event"):
                e = elem.attrib
                event_type = e["type"]

                # O(1) lookup + call all relevant handlers
                handlers = event_handlers.get(event_type)
                if handlers:
                    for handler in handlers:
                        handler(e)

                elem.clear()



    def extract_and_output(self):
        """
        Trích xuất tất cả dữ liệu từ file events trong một lần đọc và xuất ra các file output.
        Sử dụng streaming write để giảm thiểu bộ nhớ.
        """
        # Configure output streams
        file_configs = {
            "total_dist": self._config["files"]["data"]["total_dist"],
            "wait_time": self._config["files"]["data"]["wait_time"],
            "veh_flow": self._config["files"]["data"]["veh_flow"],
            "load_history": self._config["files"]["data"]["load_history"],
        }

        # Single-pass extraction with streaming output
        with StreamingMultiWriter(file_configs, self._debug) as writer:
            self.__extract_all(writer)

            # JSON debug output only available in Feather mode (buffered)
            # In CSV mode, data streams directly to files - can't get buffers
            if self._debug:
                # Write JSON from buffers before they're flushed to Feather files
                json_writer(writer.get_buffer("veh_flow"), "data/test/json/debug_traffic_flow.json")
                json_writer(writer.get_buffer("load_history"), "data/test/json/debug_bus_load_history.json")
                json_writer(writer.get_buffer("total_dist"), "data/test/json/debug_trip_total_dist.json")
                json_writer(writer.get_buffer("wait_time"), "data/test/json/debug_bus_delay.json")


