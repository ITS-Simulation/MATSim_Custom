import struct

with open("data/out/score.bin", "rb") as f:
    # '>' bắt buộc dùng Big-Endian (định dạng Java), 'd' đọc 1 số double
    score = struct.unpack(">d", f.read(8))[0]
    
print(f"System-wide LOS Score: {score}")