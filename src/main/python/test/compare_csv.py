import pandas as pd
import sys

def compare_csv(file1, file2):
    print(f"Comparing:")
    print(f"1. {file1}")
    print(f"2. {file2}")

    # Read CSVs
    df1 = pd.read_csv(file1)
    df2 = pd.read_csv(file2)

    print(f"\nRow counts:")
    print(f"File 1: {len(df1)}")
    print(f"File 2: {len(df2)}")

    # Normalize headers
    # Map Kotlin header 'schedule_headway' to Python's 'scheduled_headway'
    df1.rename(columns={"schedule_headway": "scheduled_headway"}, inplace=True)
    
    # Check column intersection
    common_cols = list(set(df1.columns) & set(df2.columns))
    print(f"\nComparing columns: {sorted(common_cols)}")

    # Sort to ensure alignment (assuming unique key vehicle+timestamp or similar)
    sort_keys = ["vehicle_id", "timestamp"]
    if all(k in common_cols for k in sort_keys):
        df1 = df1.sort_values(sort_keys).reset_index(drop=True)
        df2 = df2.sort_values(sort_keys).reset_index(drop=True)
    
    # Align rows if counts differ
    min_len = min(len(df1), len(df2))
    df1_common = df1[common_cols].iloc[:min_len]
    df2_common = df2[common_cols].iloc[:min_len]

    # Compare
    diffs = df1_common.compare(df2_common)
    
    if diffs.empty and len(df1) == len(df2):
        print("\nSUCCESS: Files are identical (ignoring header name diffs).")
    else:
        print(f"\nFound {len(diffs)} row mismatches.")
        if not diffs.empty:
            print("\nSample mismatches (first 10):")
            print(diffs)
        
        if len(df1) != len(df2):
            print(f"\nNote: Row count mismatch ({len(df1)} vs {len(df2)})")

if __name__ == "__main__":
    f1 = "data/out/bus_delay.csv"
    f2 = "data/out/stop_records.csv"
    if len(sys.argv) > 2:
        f1 = sys.argv[1]
        f2 = sys.argv[2]
    
    compare_csv(f1, f2)
