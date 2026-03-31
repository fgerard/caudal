#!/usr/bin/env python3
import os
import re
import sys
import argparse
import base64
from collections import defaultdict
from tqdm import tqdm
import random


class Histogram:
    def __init__(self, min_value: float, max_value: float, num_bins: int, max_bin_count: int = 10):
        if min_value >= max_value:
            raise ValueError("min_value must be less than max_value")
        self.min_value = min_value
        self.max_value = max_value
        if not isinstance(num_bins, int) or num_bins <= 0:
            raise ValueError("num_bins must be a positive integer")
        self.num_bins = num_bins
        self.bin_size = (max_value - min_value) / num_bins
        self.bins = [0] * num_bins
        self.max_bin_count = max_bin_count
    
    def add(self, value: float):
        if value < self.min_value or value >= self.max_value:
            return False
        bin_index = int((value - self.min_value) / self.bin_size)
        if self.bins[bin_index] < self.max_bin_count:
            self.bins[bin_index] += 1
            return True
        return False

def read_edn_line(line: str) -> dict:
    # This is a very naive EDN parser for the specific format we're expecting.
    # It won't handle nested structures or all EDN features, but it should work for our use case.
    line = line.strip()
    if line.startswith(";") or not line:
        return {}
    line = re.sub(r'^\{|\}$', '', line)  # Remove leading and trailing braces
    result = {}
    # Match key-value pairs like :key value
    matches = re.findall(r':([^\s"{}[\](),]+)\s+("[^"]*"|\S+)(?=,|$)', line)
    for key, value in matches:
        # Remove quotes from string values
        if value.startswith('"') and value.endswith('"'):
            value = value[1:-1]
        result[key] = value
    return result

def process_line(line_dict: dict, out_dir: str, event_type: str, state: dict, dry_run: bool = False, verbose: bool = False) -> bool:
    if dry_run:
        dr_str = "DRY RUN - "
    else:
        dr_str = ""
    if line_dict.get("type") == event_type:
        try:
            clip = line_dict.get("clip")
            accuracy = float(line_dict.get("accuracy", 0))
            value = line_dict.get("value")
            ai_time = line_dict.get("aiTime")
            uuid = line_dict.get("uuid")
            if clip is None or value is None or ai_time is None or uuid is None:
                return False
            skip = False
            if not state[value].add(accuracy):
                if verbose or dry_run:
                    skip = True
            out_path = os.path.join(out_dir, value, f"{value}_{accuracy:.4f}_{ai_time}_{uuid}.jpg")
            if verbose or dry_run:
                skip_str = "SKIP - " if skip else "SAVE - "
                print(f'{dr_str}{skip_str}accuracy: {accuracy}, value: {value}, ai_time: {ai_time}, uuid: {uuid}, out_path: {out_path}')
            if skip:
                return False
            if not dry_run:
                img_bytes = base64.b64decode(clip)
                os.makedirs(os.path.dirname(out_path), exist_ok=True)
                with open(out_path, "wb") as f:
                    f.write(img_bytes)
            return True
        except Exception as e:
            print(f'ERROR: {e}')
            return False
    else:
        if verbose or dry_run:
            print(f"{dr_str}Skipping line with type {line_dict.get('type')}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Extract relevant information from EDN file.")
    parser.add_argument("--input-file-or-dir", "--in", required=True, help="Path to the input EDN file or directory with EDNs.")
    parser.add_argument("--output-dir", "--out", required=True, help="Directory to save the extracted images.")
    parser.add_argument("--file-pattern", "--pattern", type=str, default=".*relevantes.*\\.edn\\.txt$", help="Regex pattern to match input files when a directory is provided. Backslashes should be escaped. Default: '.*relevantes.*\\.edn\\.txt$'")
    parser.add_argument("--event-type", type=str, default=":FACE", help="Type of entries to process (default: :FACE).")
    parser.add_argument("--min-images-per-id", "--min-imgs", type=int, default=1, help="Minimum number of images per ID to keep.")
    parser.add_argument("--max-images-per-id", "--max-imgs", type=int, default=40, help="Maximum number of images per ID to keep.")
    parser.add_argument("--min-accuracy", "--min-acc", type=float, default=0.6, help="Minimum accuracy threshold for filtering.")
    parser.add_argument("--max-accuracy", "--max-acc", type=float, default=1.0, help="Maximum accuracy threshold for filtering.")
    parser.add_argument("--num-bins", "--bins", type=int, default=10, help="Number of bins for accuracy histogram.")
    parser.add_argument("--max-bin-count", "--max-count", type=int, default=4, help="Maximum number of samples to keep per accuracy bin.")
    parser.add_argument("--dry-run", action="store_true", help="If set, will not save images but will print what would be done.")
    parser.add_argument("--verbose", "-v", action="store_true", help="Enable verbose output.")
    args = parser.parse_args()

    state = defaultdict(lambda: Histogram(args.min_accuracy, args.max_accuracy, args.num_bins, args.max_bin_count))

    if os.path.isdir(args.input_file_or_dir):
        files = [os.path.join(args.input_file_or_dir, f) for f in os.listdir(args.input_file_or_dir) if re.match(args.file_pattern, f)]
    elif os.path.isfile(args.input_file_or_dir):
        files = [args.input_file_or_dir]
    else:
        print("Invalid input path. Please provide a valid file or directory.")
        sys.exit(1)
    for file in tqdm(files, desc="Processing files"):
        with open(file, "r", encoding="utf-8") as f:
            for line in tqdm(f, desc=f"Processing {os.path.basename(file)}", leave=False):
                line_dict = read_edn_line(line)
                process_line(line_dict, args.output_dir, args.event_type, state, dry_run=args.dry_run, verbose=args.verbose)
    
    print("Purging IDs with fewer than minimum images...")
    dirs = os.listdir(args.output_dir)
    for subdir in tqdm(dirs, desc="Purging IDs"):
        subdir_path = os.path.join(args.output_dir, subdir)
        if os.path.isdir(subdir_path):
            images = [f for f in os.listdir(subdir_path) if f.endswith(".jpg")]
            if len(images) < args.min_images_per_id:
                if args.verbose or args.dry_run:
                    if args.dry_run:
                        dr_str = "DRY RUN - "
                    else:
                        dr_str = ""
                    print(f"{dr_str}DELETING: {subdir} with {len(images)} < {args.min_images_per_id} images")
                if not args.dry_run:
                    for img in images:
                        os.remove(os.path.join(subdir_path, img))
                    os.rmdir(subdir_path)
            elif len(images) > args.max_images_per_id:
                if args.verbose or args.dry_run:
                    if args.dry_run:
                        dr_str = "DRY RUN - "
                    else:
                        dr_str = ""
                    print(f"{dr_str}TRIMMING: {subdir} with {len(images)} > {args.max_images_per_id} images")
                if not args.dry_run:
                    to_delete = random.sample(images, len(images) - args.max_images_per_id)
                    for img in to_delete:
                        os.remove(os.path.join(subdir_path, img))

if __name__ == "__main__":
    main()
