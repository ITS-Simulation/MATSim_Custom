import gzip

from lxml import etree

s: dict = {}  # Lưu trữ thông tin về các chuyến xe bus

# Đọc mạng lưới MATSim
# Xe bus bắt đầu với v (v11 ..), người lái xe bắt đầu với pt_v
def make_link_length_dict(network_path: str):
    """Tao 1 dict gồm các cạnh và độ dài của chúng"""
    link_len_dict = {}

    for (_,elem) in etree.iterparse(network_path,events = ("end",), tag = "link"):
        e = elem.attrib
        if e["from"] != e["to"]:  # Loại bỏ các link loop
            link_len_dict[e["id"]] = float(e["length"])
        else:
            link_len_dict[e["id"]] = 0
        e.clear()

    return link_len_dict

def make_bus_line_dict(transit_schedule_path: str):
    """Tao 1 dict gồm các tuyến xe bus và ID xe buýt"""
    bus_line_dict = {}
    transit_tree = etree.parse(transit_schedule_path)

    for line in transit_tree.findall("transitLine"):
        line_id = line.get("id")
        veh_ids = line.xpath(".//departure/@vehicleRefId")

        for veh_id in veh_ids:
            bus_line_dict[veh_id] = line_id

    return bus_line_dict

# Đọc event từ file output_events.xml.gz"
def extract_run(output_events_path:str, link_len_dict: dict):
    with gzip.open(output_events_path, "rb") as f:

        for (event,elem) in etree.iterparse(f, events=('end',), tag = "event"):
            e = elem.attrib
            if e["type"] == "TransitDriverStarts" :
                s[e["vehicleId"]] = {
                    "veh_id": e["vehicleId"],
                    "route_id": e["transitRouteId"],
                    "depart_id": e["departureId"],
                    "depart_time": None,
                    "arrival_time": None,
                    "time_travel": None,
                    "mean_velocity": None,
                    "distance": 0,
                    "rider": 0,
                    "total_rider": 0,
                    "passenger_boarding_history": {},
                    "passenger_alight_history": {},
                    "passenger_on_board_history": {},
                    "delay_for_each_bus_stop": {},
                    "link_history":[]
                }

            # Lấy thời gian xe bus bắt đầu chạy trên mạng
            elif e["type"] == "vehicle enters traffic" and e["person"].startswith("pt_"):
                s[e["vehicle"]]["depart_time"] = e["time"]

            # Thu thập các cạnh mà chuyến xe bus đi qua đồng thời ghi nhận số người nhận trên các cạnh
            elif e["type"] == "entered link" :
                if e["vehicle"] in s.keys():
                    s[e["vehicle"]]["link_history"].append(e["link"])
                    s[e["vehicle"]]["passenger_boarding_history"][e["link"]] = 0
                    s[e["vehicle"]]["passenger_alight_history"][e["link"]] = 0
                    s[e["vehicle"]]["passenger_on_board_history"][e["link"]] = s[e["vehicle"]]["rider"]


            # Đếm số người lên, xuống 1 chuyến xe bus trên từng cạnh
            elif e["type"] == "PersonEntersVehicle" and (not e["person"].startswith("pt_")) and e["vehicle"] in s.keys():
                lastest_link = s[e["vehicle"]]["link_history"][-1]
                s[e["vehicle"]]["passenger_boarding_history"][lastest_link] += 1
                s[e["vehicle"]]["rider"] += 1
                s[e["vehicle"]]["passenger_on_board_history"][lastest_link] = s[e["vehicle"]]["rider"]
                s[e["vehicle"]]["total_rider"] += 1

            elif e["type"] == "PersonLeavesVehicle" and (not e["person"].startswith("pt_")) and e["vehicle"] in s.keys():
                lastest_link = s[e["vehicle"]]["link_history"][-1]
                s[e["vehicle"]]["passenger_alight_history"][lastest_link] += 1
                s[e["vehicle"]]["rider"] -= 1
                s[e["vehicle"]]["passenger_on_board_history"][lastest_link] = s[e["vehicle"]]["rider"]

            # Log lại thời gian delay  tại ỗi trạm cùng từng tuyến xe bus
            elif e["type"] == "VehicleArrivesAtFacility" and e["vehicle"] in s.keys():
                s[e["vehicle"]]["delay_for_each_bus_stop"][e["facility"]] = e["delay"]

            # Lấy thời gian xe bus kết thúc chạy trên mạng và tính toán các thông số liên quan rồi trả ra csv
            elif e["type"] == "vehicle leaves traffic" and e["person"].startswith("pt_"):
                assert s[e["vehicle"]]["rider"] == 0, f"Vehicle {e['vehicle']} still has {s[e['vehicle']]['rider']} passengers on board at the end of the trip."
                s[e["vehicle"]]["arrival_time"] = e["time"]
                s[e["vehicle"]]["time_travel"] = float(s[e["vehicle"]]["arrival_time"]) - float(s[e["vehicle"]]["depart_time"])
                s[e["vehicle"]]["distance"] = sum([link_len_dict[link] for link in s[e["vehicle"]]["link_history"]])
                if s[e["vehicle"]]["time_travel"] > 0:
                    s[e["vehicle"]]["mean_velocity"] = s[e["vehicle"]]["distance"] / s[e["vehicle"]]["time_travel"]
                else:
                    s[e["vehicle"]]["mean_velocity"] = 0
                yield s[e["vehicle"]]
                del s[e["vehicle"]]

            e.clear()

def extract_traffic_flow(output_events_path:str, link_len_dict: dict):
    vehicle_state = {}

    with (gzip.open(output_events_path, "rb") as f):
        for (_, elem) in etree.iterparse(f, events=('end',), tag="event"):
            e = elem.attrib
            if e["type"] == "entered link" or e["type"] == "vehicle enters traffic":
                link_length = link_len_dict[e["link"]]
                if link_length > 0:
                    vehicle_state[e["vehicle"]] = {
                        "link": e["link"],
                        "enter_time": e["time"],
                        "link_len": link_length,
                    }

            elif e["type"] == "left link" and e["vehicle"] in vehicle_state:
                vs = vehicle_state[e["vehicle"]]

                exit_time = float(e["time"])
                enter_time = float(vs["enter_time"])
                travel_duration = exit_time - enter_time
                link_len = vs["link_len"]
                # valid_record = False
                if link_length > 0 and travel_duration > 1.0:
                    avg_speed = link_len / travel_duration
                    time_ptr = enter_time

                    while time_ptr < exit_time:
                        curr_hour = int(time_ptr // 3600)
                        hour_end_time = (curr_hour + 1) * 3600
                        slice_end = float(min(exit_time, hour_end_time))
                        duration = slice_end - time_ptr

                        if duration > 0:
                            slice_dist = avg_speed * duration
                            yield {
                                "vehicle_id": e["vehicle"],
                                "link_id": vs["link"],
                                "hour": curr_hour,

                                "slice_start": time_ptr,
                                "slice_end": slice_end,
                                "duration": duration,

                                "travel_distance": slice_dist,
                                "avg_speed": avg_speed
                            }

                        time_ptr = slice_end

                del vehicle_state[e["vehicle"]]

            elif e["type"] == "vehicle leaves traffic" and e["vehicle"] in vehicle_state:
                del vehicle_state[e["vehicle"]]

            elem.clear()

def extract_segment_load_data(output_events_path: str):
    """
    Extract load data for each segment (link) with timestamps.

    Yields dictionaries with segment-level passenger load information.
    """
    vehicle_state = {}
    bus_cap = int(30 + 20 * 0.0)  # Assuming a standard bus capacity; adjust as needed

    with gzip.open(output_events_path, "rb") as f:
        for (event, elem) in etree.iterparse(f, events=('end',), tag="event"):
            e = elem.attrib

            # Initialize vehicle tracking
            if e["type"] == "TransitDriverStarts":
                vehicle_state[e["vehicleId"]] = {
                    "veh_id": e["vehicleId"],
                    "route_id": e["transitRouteId"],
                    "current_rider": 0,
                    "current_link": None,
                    "link_enter_time": None
                }

            # Track when vehicle enters a link
            elif e["type"] == "entered link" and e["vehicle"] in vehicle_state:
                vehicle_state[e["vehicle"]]["current_link"] = e["link"]
                vehicle_state[e["vehicle"]]["link_enter_time"] = float(e["time"])

            # Track when vehicle leaves a link - yield segment data
            elif e["type"] == "left link" and e["vehicle"] in vehicle_state:
                vs = vehicle_state[e["vehicle"]]
                if vs["current_link"] is not None and vs["link_enter_time"] is not None:
                    exit_time = float(e["time"])
                    time_pter = vs["link_enter_time"]
                    psr_cnt = vs["current_rider"]
                    link_id = vs["current_link"]

                    while time_pter < exit_time:
                        curr_hour = int(time_pter // 3600)
                        hour_end_time = (curr_hour + 1) * 3600
                        slice_end = min(exit_time, hour_end_time)
                        duration = slice_end - time_pter

                        if duration > 0:
                            yield {
                                "vehicle_id": vs["veh_id"],
                                "route_id": vs["route_id"],
                                "link_id": link_id,

                                "hour": curr_hour,
                                "slice_start": time_pter,
                                "slice_end": slice_end,
                                "duration": duration,

                                "passenger_load": psr_cnt,
                                "pax_second": psr_cnt * duration,

                                "plan_cap": bus_cap,
                                "plan_cap_second": bus_cap * duration,

                                "instant_load_factor": psr_cnt / bus_cap if bus_cap > 0 else 0.0
                            }

                        time_pter = slice_end

            # Update passenger count when someone boards
            elif e["type"] == "PersonEntersVehicle" and (not e["person"].startswith("pt_")) and e["vehicle"] in vehicle_state:
                vehicle_state[e["vehicle"]]["current_rider"] += 1

            # Update passenger count when someone alights
            elif e["type"] == "PersonLeavesVehicle" and (not e["person"].startswith("pt_")) and e["vehicle"] in vehicle_state:
                vehicle_state[e["vehicle"]]["current_rider"] -= 1

            # Clean up when vehicle finishes
            elif e["type"] == "vehicle leaves traffic" and e["person"].startswith("pt_"):
                if e["vehicle"] in vehicle_state:
                    del vehicle_state[e["vehicle"]]

            elem.clear()


def extract_delay_per_link_hour(output_events_path: str):
    """
    Extracts schedule deviation (delay) events, mapped to the specific link
    and time bucket where they occurred.

    This allows you to calculate 'Average Delay per Link per Hour'.
    """
    # We need state to know which LINK the bus is on when it hits a stop.
    vehicle_state = {}

    with gzip.open(output_events_path, "rb") as f:
        for (event, elem) in etree.iterparse(f, events=('end',), tag="event"):
            e = elem.attrib
            event_type = e["type"]

            # 1. Initialize Route Info
            if event_type == "TransitDriverStarts":
                vehicle_state[e["vehicleId"]] = {
                    "route_id": e["transitRouteId"],
                    "current_link": None
                }

            # 2. Track Location (Crucial to map Stop -> Link)
            elif event_type == "entered link" and e["vehicle"] in vehicle_state:
                vehicle_state[e["vehicle"]]["current_link"] = e["link"]

            # 3. THE TRIGGER: Bus Arrives at Stop (Facility)
            elif event_type == "VehicleArrivesAtFacility":
                veh_id = e["vehicle"]

                # We only care if we are tracking this PT vehicle
                if veh_id in vehicle_state:
                    vs = vehicle_state[veh_id]

                    # Ensure we know where the bus is
                    if vs["current_link"] is not None:
                        timestamp = float(e["time"])

                        # MATSim records delay in seconds (relative to schedule)
                        # Example: '120' means 2 mins late. '-60' means 1 min early.
                        raw_delay = float(e.get("delay", 0))

                        # Binning: Which hour did this happen in?
                        current_hour = int(timestamp // 3600)

                        yield {
                            "vehicle_id": veh_id,
                            "route_id": vs["route_id"],
                            "stop_id": e["facility"],
                            "link_id": vs["current_link"],

                            # Time Context
                            "timestamp": timestamp,
                            "hour": current_hour,

                            # The Metric (Schedule Deviation)
                            "schedule_deviation": raw_delay,
                        }

            # 4. Cleanup
            elif event_type == "vehicle leaves traffic" and e["person"].startswith("pt_"):
                if e["vehicle"] in vehicle_state:
                    del vehicle_state[e["vehicle"]]

            elem.clear()

if __name__ == "__main__":
    link_length_dict = make_link_length_dict("sim/pt/pt.xml")
    bus_line_dict = make_bus_line_dict("sim/pt/transit_schedule.xml")
    print(bus_line_dict)
    # result1 = extract_run("sim/pt/output/output_events.xml.gz", link_length_dict)
    # write_csv_stream(result1, "data_out/pt_vehicle_run.csv")

    # result2 = extract_traffic_flow("sim/pt/output/output_events.xml.gz", link_length_dict)
    # write_csv_stream(result2, "data_out/link_performance_per_vehicle_2.csv")

    # result3 = extract_segment_load_data("sim/pt/output/output_events.xml.gz")
    # write_csv_stream(result3, "data_out/segment_load_data_2.csv")

    # result4 = extract_trip_total_dist("sim/pt/output/output_events.xml.gz", link_length_dict)
    # write_csv_stream(result4, "data_out/pt_trip_total_distance.csv")

    # result5 = extract_delay_per_link_hour("sim/pt/output/output_events.xml.gz")
    # write_csv_stream(result5, "data_out/pt_delay_per_link_hour.csv")