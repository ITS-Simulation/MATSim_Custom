import pandas as pd

OUTPUT_DICT = "sim/pt/output"

class GetDataFromOutput:
    TRIPS_OUTPUT_FILE_CSV =  f"{OUTPUT_DICT}/output_trips.csv.gz"
    LEGS_OUTPUT_FILE_CSV  =  f"{OUTPUT_DICT}/output_legs.csv.gz"
    PERSONS_OUTPUT_FILE_CSV  =  f"{OUTPUT_DICT}/output_persons.csv.gz"

    trips = pd.read_csv(TRIPS_OUTPUT_FILE_CSV, sep = ";")
    legs = pd.read_csv(LEGS_OUTPUT_FILE_CSV, sep = ";")
    persons = pd.read_csv(PERSONS_OUTPUT_FILE_CSV, sep = ";")


    def get_quantity_of_trip_pt_mode(self):
        """
        Dùng để Tính phần trăm chuyến đi xe công cộng/ tổng xe chuyến đi được thực hiện trong 1 ngày
        (ko tính walk ra xe bus/ public transport  là 1 trip)
        :returns:
        Số lượng chuyến đi cho mỗi mode, mỗi mode có thông tin số lượng chuyến đi theo phần loại: all,  home -> work; work -> home
        """

        # pt_mode = trips[(trips["main_mode"] == "pt")].copy()
        # agg_total_pt_trip = trips.groupby("main_mode")["trip_id"].nunique()
        result = self.trips.groupby("main_mode").agg(
            total_trips = ("trip_id", "count"),
            home_work_trips = ("trip_id", lambda x:( (self.trips.loc[x.index, "start_activity_type"] == "home")  &  (self.trips.loc[x.index, "end_activity_type"] == "work")).sum()),
            work_home_trips = ("trip_id", lambda x:( (self.trips.loc[x.index, "start_activity_type"] == "work")  &  (self.trips.loc[x.index, "end_activity_type"] == "home")).sum())
        )

        # ids = self.trips.loc[lambda x: (self.trips["modes"] == "walk-pt-walk"), "person"].tolist()
        # print(f"TRIPS: ${ids}")
        return result

    def get_quantity_of_person_bus_mode(self):
        """
        Tính số lượng person khác nhau lên pt/ bus trong 1 ngày
        Tổng của số người ma các tuyến chở đi có thể lớn hơn tổng số người đi xe bus do có thể có 1 người đi làm phải đi 2 tuyến xe bus
        :return:
        trả ra số lượng người đi xe bus trên các tuyến và trên cả mạng
        """

        pt_legs = self.legs[(self.legs["transit_line"].notna())]
        result = pt_legs.groupby("transit_route").agg(
            total_person = ("person", "nunique"))

        # ids = self.legs.loc[self.legs["transit_route"].notna(), "person"].tolist()
        # print(f"LEGS: ${ids}")
        return result

    def get_total_bus_distance_by_route(self):
        pt_legs = self.legs[(self.legs["transit_line"].notna())]
        result = pt_legs.groupby("transit_route").agg(total_km_distance = ("distance", "sum"))
        # pt_legs = self.legs[(self.legs["transit_line"].notna())]
        # result = self.legs.groupby(self.legs["mode"] == "car").agg(
        # total_km_distance = ("distance", "sum"))

        return result

if __name__ == "__main__":
    get = GetDataFromOutput()
    print(get.get_quantity_of_person_bus_mode())
    print(get.get_quantity_of_trip_pt_mode())
    print(get.get_total_bus_distance_by_route())
