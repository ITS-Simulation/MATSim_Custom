import yaml
import csv
import json

from pathlib import Path
from typing import Optional, Generator, Any
import pandas as pd
from pandas import DataFrame


def _get_file_path(file_name: str, debug: bool) -> Path:
    """Get file path with appropriate extension based on debug flag."""
    file_path = Path(file_name)
    if not debug:
        file_path = file_path.with_suffix(".feather")
    return file_path


def dataframe_to_file(
    df: DataFrame,
    file_name: str,
    debug: bool = True,
    index: bool = True,
    index_label: Optional[str | list[str]] = None
):
    """
    Write DataFrame to file. Format depends on debug flag:
    - debug=True: CSV format
    - debug=False: Feather format (faster I/O)
    """
    file_path = _get_file_path(file_name, debug)
    file_path.parent.mkdir(parents=True, exist_ok=True)

    if not debug:
        if index and df.index.name is not None:
            df = df.reset_index()
        df.to_feather(file_path)
    else:
        assert index or index_label is None, "index_label should be None if index is False"
        df.to_csv(file_path, index=index, index_label=index_label, encoding="utf-8")


def read_dataframe(
    file_name: str,
    debug: bool = True,
    usecols: Optional[list[str]] = None,
    dtype: Optional[dict] = None,
    index_col: Optional[int | str] = None
) -> DataFrame:
    """
    Read DataFrame from file. Format depends on debug flag:
    - debug=True: CSV format
    - debug=False: Feather format (faster I/O)
    """
    file_path = _get_file_path(file_name, debug)

    if not debug:
        # For Feather: read all columns first, then set index and filter
        df = pd.read_feather(file_path)
        
        # Set index if specified
        if index_col is not None:
            if isinstance(index_col, int) and usecols:
                index_col_name = usecols[index_col]
            else:
                index_col_name = index_col
            if index_col_name in df.columns:
                df = df.set_index(index_col_name)
        
        # Filter columns if specified (after setting index)
        if usecols:
            # Only keep columns that exist (index is already set)
            cols_to_keep = [c for c in usecols if c in df.columns]
            df = df[cols_to_keep]
        
        return df
    else:
        return pd.read_csv(file_path, sep=",", header=0, usecols=usecols, dtype=dtype, index_col=index_col)


def write_data_stream(results: Generator, filename: str, debug: bool = True):
    """
    Write data stream to file. Format depends on debug flag:
    - debug=True: CSV format (streaming)
    - debug=False: Feather format (requires collecting to DataFrame)
    """
    file_path = _get_file_path(filename, debug)
    file_path.parent.mkdir(parents=True, exist_ok=True)

    if not debug:
        # Feather: collect generator to DataFrame, then write
        df = pd.DataFrame(list(results))
        df.to_feather(file_path)
    else:
        # CSV: streaming write
        with open(file_path, "w", newline="", encoding="utf-8") as f:
            writer = None
            for row in results:
                if writer is None:
                    writer = csv.DictWriter(f, fieldnames=row.keys())
                    writer.writeheader()
                writer.writerow(row)


class StreamingMultiWriter:
    """
    Manages multiple output streams for single-pass extraction.
    Writes records immediately during iteration to minimize memory usage.
    
    For CSV (debug=True): True streaming, records written immediately.
    For Feather (debug=False): Buffered in memory, written on close.
    """

    def __init__(self, file_configs: dict[str, str], debug: bool):
        """
        Initialize streaming writers for multiple output files.
        
        Args:
            file_configs: Dict mapping stream_name -> filename
            debug: If True, use CSV (streaming). If False, use Feather (buffered).
        """
        self._debug = debug
        self._streams = {}  # stream_name -> (file_handle, csv_writer) for CSV
        self._buffers = {}  # stream_name -> list[dict] for Feather
        self._file_paths = {}  # stream_name -> Path

        for stream_name, filename in file_configs.items():
            file_path = _get_file_path(filename, debug)
            file_path.parent.mkdir(parents=True, exist_ok=True)
            self._file_paths[stream_name] = file_path

            if debug:
                # CSV: Open file handle immediately
                f = open(file_path, "w", newline="", encoding="utf-8")
                self._streams[stream_name] = {"file": f, "writer": None}
            else:
                # Feather: Buffer in memory
                self._buffers[stream_name] = []

    def write(self, stream_name: str, record: dict):
        """Write a single record to the specified stream."""
        if self._debug:
            stream = self._streams[stream_name]
            if stream["writer"] is None:
                stream["writer"] = csv.DictWriter(stream["file"], fieldnames=record.keys())
                stream["writer"].writeheader()
            stream["writer"].writerow(record)
        else:
            self._buffers[stream_name].append(record)

    def write_many(self, stream_name: str, records: list[dict]):
        """Write multiple records to the specified stream."""
        for record in records:
            self.write(stream_name, record)

    def close(self):
        """Close all streams and flush buffers to files."""
        if self._debug:
            # CSV: Just close file handles
            for stream_name, stream in self._streams.items():
                stream["file"].close()
        else:
            # Feather: Write buffered data to files
            for stream_name, buffer in self._buffers.items():
                if buffer:
                    df = pd.DataFrame(buffer)
                    df.to_feather(self._file_paths[stream_name])

    def get_buffer(self, stream_name: str) -> list[dict]:
        """Get buffered data for a stream (for debug JSON output)."""
        if self._debug:
            return []  # CSV mode doesn't buffer
        return self._buffers.get(stream_name, [])

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        return False


def yaml_parser(file: str) -> dict:
    try:
        with open(file, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f)
            f.close()
        return config
    except FileNotFoundError:
        print("Config file not found")
        raise FileNotFoundError("Config file not found")
    except Exception as e:
        print(f"Error loading config file: {e}")
        raise e


def json_writer(data: dict | list[Any], file_name: str):
    file_path = Path(file_name)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=4)
            f.close()
    except Exception as e:
        print(f"Error writing JSON file: {e}")
        raise e


def json_loader(file: str) -> dict:
    try:
        with open(file, "r", encoding="utf-8") as f:
            data = json.load(f)
            f.close()
        return data
    except FileNotFoundError:
        print("JSON file not found")
        raise FileNotFoundError("JSON file not found")
    except Exception as e:
        print(f"Error loading JSON file: {e}")
        raise e
