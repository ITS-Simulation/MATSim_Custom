import pandas as pd

output_dict = "D:/Cong viec tai VTS/3. Hoc MATSim/Code/Public_Transport_MATSIM/output/pt-tutorial"
data_file = "D:/Cong viec tai VTS/3. Hoc MATSim/Code/Public_Transport_MATSIM/src/main/data/publicTransportData.json"

legs = pd.read_csv(f"{output_dict}/output_legs.csv.gz", sep = ";")
trips = pd.read_csv(f"{output_dict}/output_trips.csv.gz", sep = ";")

#Xác định tài xế
#

# def time_to_sec(t):
#     if pd.isna(t): return 0
#     return sum(int(x) * 60**i for i, x in enumerate(reversed(t.split(':'))))
#
# drivers = legs[
#     (legs['mode'] == 'pt')    ].copy()
#
# drivers['sec'] = drivers['trav_time'].apply(time_to_sec)
#
# result = drivers.groupby('transit_route').agg(
#     avg_speed_kmh=('distance', lambda x: (x.sum()/1000) / (drivers.loc[x.index, 'sec'].sum()/3600)),
#     total_trips=('vehicle_id', 'nunique')
# ).round(2).reset_index()


drivers = legs[ (legs['mode'] == 'pt')    ].copy()


agg_person_number = legs.groupby('mode')['trip_id'].nunique()


agg_person_number_per_bus_id = drivers.groupby('vehicle_id')['trip_id'].nunique()  #Lấy tổng số người trên mỗi xe buýt
agg_person_number_per_route_id = drivers.groupby('transit_route')['trip_id'].nunique() # Lấy tổng số người trên mỗi tuyến buýt
agg_totalKm_person_move_with_bus_id = drivers.groupby('vehicle_id')['distance'].sum()  #Lấy tổng số người trên mỗi xe buýt

print(agg_person_number)
print(agg_person_number_per_bus_id)
print(agg_person_number_per_route_id)
print(agg_totalKm_person_move_with_bus_id)
# result  = agg_person_number.reset_index().rename(columns={'person': 'unique_person_count'})
# # XUẤT RA JSON – KOTLIN ĐỌC NGON LÀNH!
# result.to_json('bus_speed_stats.json', orient='records', indent=2)
#
# # XUẤT RA JSON – KOTLIN ĐỌC NGON LÀNH!
# result.to_json(data_file, orient='records', indent=2)
