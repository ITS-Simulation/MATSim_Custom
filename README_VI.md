# Pipeline Xử Lý Bus MATSim

Đây là một pipeline hậu xử lý và tính điểm (scoring) dựa trên Kotlin cho dữ liệu mô phỏng xe buýt MATSim. Pipeline sử dụng DuckDB để xử lý dữ liệu hiệu năng cao và tính toán điểm Mức độ Phục vụ (Level of Service - LOS) dựa trên tiêu chuẩn TCQSM.

## Cách Sử Dụng

### Tải Xuống
Bạn có thể tải file JAR đã build sẵn từ trang [GitHub Releases](https://github.com/ITS-Simulation/MATSim_Custom/releases).

### Build
Hoặc tự build file shadow jar (fat jar):
```bash
./gradlew shadowJar
```

Chạy ứng dụng:
```bash
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar build/libs/Bus_MATSim-1.0-SNAPSHOT.jar \
  --cfg data/config/config.yaml \
  --matsim-cfg data/config/matsim_config.xml \
  --out data/out/final_scores.bin
```

**Lưu ý**: Đây chỉ là ví dụ. Bạn có thể thay đổi đường dẫn input/output và các tham số khác theo nhu cầu.

### Tham Số Dòng Lệnh (Command Line Arguments)
*   `--cfg`: Đường dẫn đến file cấu hình YAML của ứng dụng (**Bắt buộc**).
*   `--matsim-cfg`: Đường dẫn đến file cấu hình MATSim (**Bắt buộc**).
*   `--out`: Đường dẫn file kết quả dạng binary (**Bắt buộc**).
*   `--agg`: Chiến lược tổng hợp. Giá trị: `passenger_time`, `passenger_trip`, `operator_veh_time`, `operator_load` (Mặc định: `passenger_time`).
*   `--log-file`: Đường dẫn file log tùy chỉnh (Mặc định: `logs/app.log`).
*   `--matsim-log`: Bật logging của MATSim (Mặc định: `false` - Tắt).
*   `--signature`: Chữ ký (signature) của worker để ghi log (Mặc định: Hostname).

## Cấu Hình
Pipeline được điều khiển bởi một file cấu hình YAML (mặc định: `config.yaml`).

### Các Phần Chính
*   **files.data**: Đường dẫn Input/Output cho các file Arrow/CSV.
*   **scoring**: Các tham số để tính toán LOS (trọng số, ngưỡng, hệ số phạt).
    *   **wait_ride**: Độ co giãn (elasticity) và thời gian di chuyển cơ sở.
    *   **amenity**: Sự có mặt của nhà chờ/ghế ngồi.
    *   **ped_env**: Các thuộc tính vật lý của đường phố (chiều rộng làn, vùng đệm) cho Điểm Người Đi Bộ (Pedestrian Score).

## Logging (Ghi Nhật Ký)
Ứng dụng sử dụng Log4j2 với cấu hình động. Các mức mặc định là:
*   **Root**: `WARN` (Ẩn các thư viện ồn ào)
*   **App**: `INFO` (Hiển thị các bước xử lý chính)
*   **MATSim**: `ERROR` (Ẩn tất cả cảnh báo từ MATSim khi đọc event)

### Ghi Đè Mức Log
Bạn có thể thay đổi mức log khi chạy (runtime) bằng cách sử dụng các thuộc tính hệ thống JVM:

```bash
# Debug logic ứng dụng, xem info của MATSim
java -Dlog.level.app=debug -Dlog.level.matsim=info -jar ...
```
*   `-Dlog.level.root=...`
*   `-Dlog.level.app=...`
*   `-Dlog.level.matsim=...`

## Đầu Ra (Outputs)
1.  **Trung gian**: `data/temp/merged_los.arrow` (Các chỉ số thô đã qua xử lý)
2.  **Cuối cùng**: File được chỉ định bởi `--out` (Định dạng Binary), chứa một **Số thực Big-Endian (Double)** duy nhất đại diện cho điểm tổng hợp toàn hệ thống.

### Khuyến Nghị Tiêu Chí Tối Ưu

| Mục Tiêu Tối Ưu                       | Tiêu Chí Tốt Nhất    | Tại Sao?                                                                                    |
|:--------------------------------------|:---------------------|:--------------------------------------------------------------------------------------------|
| **Tối Đa Sự Hài Lòng Của Hành Khách** | **`PASSENGER_TIME`** | Tối ưu hóa "hạnh phúc" theo trọng số thời gian. Phạt nặng việc bị kẹt (lãng phí thời gian). |
| **Tối Đa Lưu Lượng**                  | `PASSENGER_TRIP`     | Tối ưu hóa việc di chuyển người đi xa. Bỏ qua sự chậm trễ do tắc nghẽn.                     |
| **Tối Thiểu Chi Phí Vận Hành**        | `OPERATOR_VEH_TIME`  | Tối ưu hóa tốc độ/dòng chảy của xe buýt. Bỏ qua việc có ai trên xe hay không.               |
| **Tối Đa Sử Dụng Tài Sản**            | `OPERATOR_LOAD`      | Tối ưu hóa việc triển khai xe buýt lớn trên các tuyến ngắn, nhanh. (Trừu tượng).            |

### Đọc File Binary Đầu Ra
File đầu ra chứa chính xác 8 bytes (1 số double 8-byte).

#### Java / Kotlin
```kotlin
import java.io.DataInputStream
import java.io.FileInputStream

DataInputStream(FileInputStream("final_scores.bin")).use {
    val score = it.readDouble()
    println("System-wide LOS Score: $score")
}
```

#### Python
```python
import struct

with open("final_scores.bin", "rb") as f:
    # '>' bắt buộc dùng Big-Endian (định dạng Java), 'd' đọc 1 số double
    score = struct.unpack(">d", f.read(8))[0]
    
print(f"System-wide LOS Score: {score}")
```

## Sử Dụng Docker
Để chạy ứng dụng này trong Docker (Linux), bạn cần **cài đặt sẵn (pre-install) các extension DuckDB** trong quá trình build. Nếu không, ứng dụng sẽ gặp lỗi nếu container runtime không có kết nối internet.

### Ví Dụ Dockerfile
```dockerfile
FROM azul/zulu-openjdk:21

# 1. Cài đặt DuckDB CLI để tải extension
RUN apt-get update && apt-get install -y wget unzip \
    && wget https://github.com/duckdb/duckdb/releases/download/v1.1.2/duckdb_cli-linux-amd64.zip \
    && unzip duckdb_cli-linux-amd64.zip -d /usr/local/bin \
    && rm duckdb_cli-linux-amd64.zip

# 2. Tải sẵn extension 'arrow' vào /root/.duckdb/extensions/...
# Bước này chạy 1 lần lúc build, nên lúc chạy (runtime) không cần internet nữa
RUN duckdb -c "INSTALL arrow; LOAD arrow;"

# 3. Copy Ứng Dụng
COPY build/libs/Bus_MATSim-1.0-SNAPSHOT.jar app.jar

# 4. Chạy
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "app.jar"]
```
