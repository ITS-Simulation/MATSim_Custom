
raw_bus_data_file = "D:/Cong viec tai VTS/3. Hoc MATSim/Code/Public_Transport_MATSIM/src/main/python/MATSim Output Processor/pt_vehicle_run.csv"
work_bank_file = "D:/Cong viec tai VTS/3. Hoc MATSim/Code/Public_Transport_MATSIM/src/main/python/MATSim Output Processor/work_bank_bus_data.csv"

import pandas as pd
import matplotlib.pyplot as plt
import csv

def velocity_data_bus_system(raw_bus_data_path):
    df = pd.read_csv(raw_bus_data_path)
    mean_vel_per_hour = []
    for i in range(24):
        per_hour = df[(df['depart_time'] >= i*3600) & (df['depart_time'] < (i+1)*3600)]
        mean_vel = per_hour['mean_velocity'].mean()
        mean_vel_per_hour.append(mean_vel)
    #   Tính vận tốc trung bình cả ngày (để vẽ đường ngang tham chiếu)
    overall_mean = df['mean_velocity'].mean()
    def draw_plot(mean_vel_per_hour, overall_mean):
        hours = list(range(24))
        plt.figure(figsize=(12, 6))
        plt.plot(hours, mean_vel_per_hour, marker='o', linewidth=2.5, markersize=6, color='#1f77b4', label='Vận tốc trung bình theo giờ')
        # Đường ngang thể hiện vận tốc trung bình cả ngày
        plt.axhline(y=overall_mean, color='red', linestyle='--', linewidth=2, label=f'Trung bình cả ngày: {overall_mean:.2f} km/h')
        # Tô màu nền cho giờ cao điểm (ví dụ 6h-9h và 16h-19h)
        plt.axvspan(6, 9, alpha=0.2, color='orange', label='Giờ cao điểm sáng')
        plt.axvspan(16, 19, alpha=0.2, color='orange', label='Giờ cao điểm chiều')
        plt.title('Vận tốc trung bình của xe buýt theo giờ trong ngày', fontsize=16, fontweight='bold', pad=20)
        plt.xlabel('Giờ trong ngày', fontsize=12)
        plt.ylabel('Vận tốc trung bình (km/h)', fontsize=12)
        plt.xticks(hours)  # hiện đủ 24 giờ
        plt.grid(True, alpha=0.3)
        plt.legend()
        plt.tight_layout()
        plt.show()
    draw_plot(mean_vel_per_hour,overall_mean)
    return mean_vel_per_hour, overall_mean

def velocity_data_bus_line(raw_bus_data_path):
    pass

velocity_data_bus_system(raw_bus_data_file)