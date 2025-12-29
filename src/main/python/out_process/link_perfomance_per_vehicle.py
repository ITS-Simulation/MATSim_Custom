import pandas as pd

def calc_mean_velocity_per_link(df_root):
    df = df_root.copy()
    mean_vel_per_link_df = df.groupby("link")["mean_velocity_of_veh_in_link"].mean().reset_index()
    print(mean_vel_per_link_df)

def calc_mean_velocity_per_link_for_bus(df_root):
    df = df_root.copy()
    df["is_bus"] = df.apply(lambda row: row['veh_id'].startswith("v"), axis=1)
    bus_df = df[df["is_bus"] == True]
    mean_vel_per_link_bus_df = bus_df.groupby("link")["mean_velocity_of_veh_in_link"].mean().reset_index()
    mean_vel_per_link_bus_df.rename(columns= {"mean_velocity_of_veh_in_link":"mean_velocity_of_bus_in_link"})
    print(mean_vel_per_link_bus_df)


csv_file = "D:/Cong viec tai VTS/3. Hoc MATSim/Code/Public_Transport_MATSIM/src/main/python/MATSim Output Processor/link_perfomance_per_vehicle.csv"
df_root = pd.read_csv(csv_file)
calc_mean_velocity_per_link(df_root)
calc_mean_velocity_per_link_for_bus(df_root)