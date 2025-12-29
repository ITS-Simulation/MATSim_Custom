import gzip
import pandas as pd

from lxml import etree
from scoring.utility import (
    yaml_parser,
    json_writer,
    dataframe_to_file,
    StreamingMultiWriter,
)


class MATSimExtractorV2:
    def __init__(self, config: str):
        self._config = yaml_parser(config)
        self._debug = self._config["mode"] == "debug"
        self.event = self._config["files"]["out"]["events"]

        self._link_data_dict = {}
        self.__make_link_data_dict()

        self._line_headway_dict = {}
        self.__make_line_headway_dict()

        # Create blacklist of non-bus vehicles and set of bus vehicles
        self.vehicle_blacklist = set()
        self._bus_vehicles = set()
        self.__create_vehicle_sets()

        self.__output_metadata()

        if self._debug:
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

    def __make_line_headway_dict(self):
        """
        Tạo dict gồm headway theo lịch trình cho các tuyến bus và tính bus frequency trên mỗi link.

        Logic aligned with Kotlin MATSimMetadataStore:
        - Apply frequency to ALL route links (not just stop links)
        - Use departures.size / operatingHours for frequency
        - Accumulate frequency for links used by multiple routes
        """

        def parse_departure_times(dep_times_str: list[str]) -> list[float]:
            """Chuyển đổi chuỗi HH:MM:SS thành giây duy nhất được sắp xếp"""
            dep_times_sec = []
            for t in dep_times_str:
                parts = t.split(":")
                seconds = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
                dep_times_sec.append(seconds)

            return sorted(set(dep_times_sec))

        transit_tree = etree.parse(self._config["files"]["inp"]["transit"])

        for line in transit_tree.findall("transitLine"):
            # Filter: only process lines with matching transport mode from config
            allowed_modes = set(
                self._config["matsim"].get("bus_transport_modes", ["bus", "pt"])
            )
            transport_modes = set(line.xpath(".//transitRoute/transportMode/text()"))
            if not transport_modes or not transport_modes & allowed_modes:
                continue

            line_id = line.get("id")

            # Trích xuất thời gian khởi hành và tính toán headway theo lịch trình
            # Collect all departures across all routes for this line
            all_dep_times_str = line.xpath(".//departure/@departureTime")
            if len(all_dep_times_str) >= 2:
                all_dep_times = parse_departure_times(all_dep_times_str)

                # Headway = duration / (num_departures - 1), matching Kotlin logic
                duration = (all_dep_times[-1] - all_dep_times[0]) / (
                    len(all_dep_times) - 1
                )
                self._line_headway_dict[line_id] = duration
            else:
                # Chỉ có một chuyến khởi hành, không thể tính headway
                self._line_headway_dict[line_id] = None

            # Tính tần suất xe buýt - Kotlin aligned logic
            # Apply frequency to ALL route links (not just stop links)
            for route in line.findall("transitRoute"):
                # Lấy tất cả các link trong lộ trình
                route_links = route.xpath(".//route/link/@refId")

                # Lấy thời gian khởi hành cho lộ trình này
                route_deps_str = route.xpath(".//departure/@departureTime")
                if not route_deps_str:
                    continue

                dep_times = parse_departure_times(route_deps_str)
                if not dep_times:
                    continue

                op_hours = max(1.0, (dep_times[-1] - dep_times[0]) / 3600.0)
                freq = len(dep_times) / op_hours

                # Apply frequency to ALL route links (merge with accumulation)
                for link_id in route_links:
                    if link_id in self._link_data_dict:
                        self._link_data_dict[link_id]["bus_freq"] += freq

    def __create_vehicle_sets(self):
        """
        Tạo blacklist xe không phải bus và tập hợp bus vehicles.
        Blacklist bao gồm các xe có vehicle type KHÔNG bắt đầu với bus_type_prefix.
        Ví dụ: tram, train, etc. sẽ bị loại trừ khỏi việc theo dõi.
        """
        vehicle_file = self._config["files"]["inp"]["transit_vehicles"]
        bus_type_prefix = self._config["matsim"]["bus_type_prefix"]

        vehicle_tree = etree.parse(vehicle_file)

        # Lấy tất cả vehicle types và phân loại
        bus_types = set()
        non_bus_types = set()
        for veh_type in vehicle_tree.xpath(".//*[local-name()='vehicleType']"):
            type_id = veh_type.get("id")
            if type_id:
                if type_id.lower().startswith(bus_type_prefix.lower()):
                    bus_types.add(type_id)
                else:
                    non_bus_types.add(type_id)

        # Phân loại xe vào blacklist hoặc bus_vehicles
        for vehicle in vehicle_tree.xpath(".//*[local-name()='vehicle']"):
            veh_id = vehicle.get("id")
            veh_type = vehicle.get("type")
            if veh_type in non_bus_types:
                self.vehicle_blacklist.add(veh_id)
            elif veh_type in bus_types:
                self._bus_vehicles.add(veh_id)

        if self._debug:
            print(
                f"Created blacklist with {len(self.vehicle_blacklist)} non-bus vehicles"
            )
            print(f"Created bus set with {len(self._bus_vehicles)} bus vehicles")
            print(f"Non-bus vehicle types: {non_bus_types}")
            print(f"Bus vehicle types: {bus_types}")

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

        Aligned with Kotlin logic:
        - LinkEventHandler: Outputs link_records (one per link traversal)
        - BusDelayHandler: Outputs stop_records (bus stop events)

        Args:
            writer: StreamingMultiWriter để ghi kết quả trực tiếp
        """

        # ====================================================================
        # STATE DICTIONARIES
        # Combined vehicle state for link records (aligned with Kotlin VehicleState)
        # ====================================================================
        vehicle_state = (
            {}
        )  # vehicle_id -> {current_link, enter_time, line_id, passenger_count, is_bus}
        stop_state = (
            {}
        )  # vehicle_id -> {current_link, line_id, stop_id, arrival_time, boarding, alighting}

        # ====================================================================
        # HELPER FUNCTIONS
        # ====================================================================
        def get_or_create_vehicle_state(vehicle_id: str) -> dict:
            """Get or create vehicle state for tracking link traversals."""
            if vehicle_id not in vehicle_state:
                vehicle_state[vehicle_id] = {
                    "current_link": None,
                    "enter_time": None,
                    "line_id": None,
                    "passenger_count": 0,
                    "is_bus": vehicle_id in self._bus_vehicles,
                }
            return vehicle_state[vehicle_id]

        def get_or_create_stop_state(vehicle_id: str) -> dict | None:
            """Get or create stop state for bus delay tracking. Returns None for non-bus or blacklisted."""
            # Match Kotlin: only track vehicles in bus set (implicitly excludes blacklist)
            if vehicle_id not in self._bus_vehicles:
                return None
            if vehicle_id not in stop_state:
                stop_state[vehicle_id] = {
                    "current_link": None,
                    "line_id": None,
                    "stop_id": None,
                    "arrival_time": None,
                    "boarding": 0,
                    "alighting": 0,
                    "delay": -1.0,  # Match Kotlin default
                }
            return stop_state[vehicle_id]

        def write_link_record(vehicle_id: str, exit_time: float):
            """Write a link record for completed link traversal."""
            vs = vehicle_state.get(vehicle_id)
            if vs is None:
                return

            link_id = vs["current_link"]
            enter_time = vs["enter_time"]
            if link_id is None or enter_time is None:
                return

            link_meta = self._link_data_dict.get(link_id)
            if link_meta is None:
                return

            duration = exit_time - enter_time
            if duration < 1.0:
                return

            writer.write(
                "link_records",
                {
                    "vehicle_id": vehicle_id,
                    "link_id": link_id,
                    "line_id": vs["line_id"],
                    "enter_time": enter_time,
                    "exit_time": exit_time,
                    "travel_distance": link_meta["len"],
                    "passenger_load": vs["passenger_count"] if vs["is_bus"] else None,
                    "is_bus": vs["is_bus"],
                },
            )

        # ====================================================================
        # HANDLER FUNCTIONS - Aligned with Kotlin handlers
        # ====================================================================

        # --- TransitDriverStarts ---
        def handle_transit_driver_starts(e):
            """Initialize state for transit driver (bus)."""
            vehicle_id = e["vehicleId"]
            if vehicle_id in self.vehicle_blacklist:
                return

            vs = get_or_create_vehicle_state(vehicle_id)
            vs["line_id"] = e.get("transitLineId")  # Get from event, not static dict
            vs["is_bus"] = vehicle_id in self._bus_vehicles

            # Also initialize stop state for bus
            ss = get_or_create_stop_state(vehicle_id)
            if ss is not None:
                ss["line_id"] = e.get(
                    "transitLineId"
                )  # Get from event, not static dict

        # --- VehicleEntersTraffic ---
        def handle_vehicle_enters_traffic(e):
            """Vehicle enters traffic, set initial link state."""
            vehicle_id = e["vehicle"]
            if vehicle_id in self.vehicle_blacklist:
                return

            vs = get_or_create_vehicle_state(vehicle_id)
            vs["current_link"] = e["link"]
            vs["enter_time"] = float(e["time"])

        # --- LinkEnter ---
        def handle_link_enter(e):
            """Handle link enter event."""
            vehicle_id = e["vehicle"]
            if vehicle_id in self.vehicle_blacklist:
                return

            vs = get_or_create_vehicle_state(vehicle_id)
            vs["current_link"] = e["link"]
            vs["enter_time"] = float(e["time"])

            # Also update stop state link for buses
            ss = get_or_create_stop_state(vehicle_id)
            if ss is not None:
                ss["current_link"] = e["link"]

        # --- LinkLeave ---
        def handle_link_leave(e):
            """Handle link leave - write link record."""
            vehicle_id = e["vehicle"]
            write_link_record(vehicle_id, float(e["time"]))

            # Clear link state
            vs = vehicle_state.get(vehicle_id)
            if vs is not None:
                vs["current_link"] = None
                vs["enter_time"] = None

        # --- VehicleLeavesTraffic ---
        def handle_vehicle_leaves_traffic(e):
            """Vehicle leaves traffic - write final link record and cleanup."""
            vehicle_id = e["vehicle"]

            vs = vehicle_state.get(vehicle_id)
            if vs is not None:
                # Write final link record if there's pending data
                if vs["current_link"] is not None and vs["enter_time"] is not None:
                    write_link_record(vehicle_id, float(e["time"]))
                vehicle_state.pop(vehicle_id, None)

        # --- PersonEntersVehicle ---
        def handle_person_enters_vehicle(e):
            """Person boards vehicle (non-driver)."""
            if e["person"].startswith("pt_"):
                return

            vehicle_id = e["vehicle"]
            vs = vehicle_state.get(vehicle_id)
            if vs is not None:
                vs["passenger_count"] += 1

            # Track boarding at stop for buses
            ss = stop_state.get(vehicle_id)
            if ss is not None and ss["stop_id"] is not None:
                ss["boarding"] += 1

        # --- PersonLeavesVehicle ---
        def handle_person_leaves_vehicle(e):
            """Person alights vehicle (non-driver)."""
            if e["person"].startswith("pt_"):
                return

            vehicle_id = e["vehicle"]
            vs = vehicle_state.get(vehicle_id)
            if vs is not None:
                vs["passenger_count"] -= 1

            # Track alighting at stop for buses
            ss = stop_state.get(vehicle_id)
            if ss is not None and ss["stop_id"] is not None:
                ss["alighting"] += 1

        # --- VehicleArrivesAtFacility ---
        def handle_vehicle_arrives_at_facility(e):
            """Bus arrives at stop facility."""
            vehicle_id = e["vehicle"]
            ss = stop_state.get(vehicle_id)
            if ss is None:
                return

            facility_id = e["facility"]
            ss["stop_id"] = facility_id
            ss["current_link"] = ss["current_link"] or "undefined"
            ss["arrival_time"] = float(e["time"])
            ss["boarding"] = 0
            ss["alighting"] = 0
            ss["delay"] = float(e["delay"])

        # --- VehicleDepartsAtFacility ---
        def handle_vehicle_departs_at_facility(e):
            """Bus departs from stop - write stop record."""
            vehicle_id = e["vehicle"]
            ss = stop_state.get(vehicle_id)
            if ss is None:
                return

            stop_id = ss["stop_id"]
            arrival_time = ss["arrival_time"]
            line_id = ss["line_id"]
            link_id = ss["current_link"]

            if (
                stop_id is None
                or arrival_time is None
                or line_id is None
                or link_id is None
            ):
                return

            headway = self._line_headway_dict.get(line_id)
            if headway is None:
                return

            # Get headway tolerance from config (in minutes), convert to seconds
            headway_tolerance = (
                float(self._config.get("wait_ride", {}).get("headway_tolerance", 1))
                * 60.0
            )

            # Get delay - use from departs event (XML standard), fallback to stored arrival delay
            delay_val = ss["delay"]

            # Match Kotlin logic: delay.takeIf { it >= -headwayTolerance * 60.0 } ?: headway
            if delay_val >= -headway_tolerance:
                schedule_dev = delay_val
            else:
                schedule_dev = headway

            writer.write(
                "stop_records",
                {
                    "vehicle_id": vehicle_id,
                    "stop_id": stop_id,
                    "link_id": link_id,
                    "line_id": line_id,
                    "timestamp": arrival_time,
                    "schedule_deviation": schedule_dev,
                    "scheduled_headway": headway,
                    "boarding": ss["boarding"],
                    "alighting": ss["alighting"],
                },
            )

            # Reset stop state - match Kotlin
            ss["stop_id"] = None
            ss["arrival_time"] = None
            ss["boarding"] = 0
            ss["alighting"] = 0
            ss["delay"] = -1.0  # Match Kotlin reset value

        # ====================================================================
        # DISPATCH TABLE
        # Maps event_type -> list of handlers to call
        # ====================================================================
        event_handlers = {
            # Transit driver starts (bus only)
            "TransitDriverStarts": [handle_transit_driver_starts],
            # Link enter events
            "entered link": [handle_link_enter],
            "vehicle enters traffic": [handle_vehicle_enters_traffic],
            # Link exit events
            "left link": [handle_link_leave],
            "vehicle leaves traffic": [handle_vehicle_leaves_traffic],
            # Person events
            "PersonEntersVehicle": [handle_person_enters_vehicle],
            "PersonLeavesVehicle": [handle_person_leaves_vehicle],
            # Facility events (bus delay)
            "VehicleArrivesAtFacility": [handle_vehicle_arrives_at_facility],
            "VehicleDepartsAtFacility": [handle_vehicle_departs_at_facility],
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

        V2 outputs:
        - link_records: One record per link traversal
        - stop_records: Bus stop events
        """
        # Configure output streams (V2: 2 outputs instead of 4)
        file_configs = {
            "link_records": self._config["files"]["data"]["link_records"],
            "stop_records": self._config["files"]["data"]["stop_records"],
        }

        # Single-pass extraction with streaming output
        with StreamingMultiWriter(file_configs, self._debug) as writer:
            self.__extract_all(writer)

            # JSON debug output only available in Feather mode (buffered)
            if self._debug:
                json_writer(
                    writer.get_buffer("link_records"),
                    "data/test/json/debug_link_records.json",
                )
                json_writer(
                    writer.get_buffer("stop_records"),
                    "data/test/json/debug_stop_records.json",
                )
