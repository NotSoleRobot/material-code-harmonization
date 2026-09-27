import os
import zipfile

def create_source_zip():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    # Save the zip in root directory as NUMM_Material_Code_Harmonization_Source.zip
    output_zip_path = os.path.join(root_dir, "NUMM_Material_Code_Harmonization_Source.zip")

    exclude_dirs = {
        "node_modules",
        "target",
        "dist",
        ".git",
        "__pycache__",
        ".pytest_cache",
        ".venv",
        "venv",
        ".idea",
        ".vscode",
        ".system_generated",
        "postgres_data",
        ".gemini"
    }

    exclude_files = {
        ".DS_Store",
        "Thumbs.db",
        "NUMM_Material_Code_Harmonization_Source.zip"
    }

    print(f"Creating clean source zip from: {root_dir}")
    file_count = 0
    total_size = 0

    with zipfile.ZipFile(output_zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
        for root, dirs, files in os.walk(root_dir):
            # Modify dirs in-place to skip excluded directories
            dirs[:] = [d for d in dirs if d not in exclude_dirs and not d.startswith(".")]

            for file in files:
                if file in exclude_files or file.endswith(".pyc") or file.endswith(".log"):
                    continue

                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, root_dir)

                # Check if path contains any excluded directory segment
                path_parts = rel_path.replace("\\", "/").split("/")
                if any(part in exclude_dirs for part in path_parts):
                    continue

                zipf.write(full_path, rel_path)
                file_count += 1
                total_size += os.path.getsize(full_path)

    zip_size_mb = os.path.getsize(output_zip_path) / (1024 * 1024)
    print(f"Successfully created: {output_zip_path}")
    print(f"Total files included: {file_count}")
    print(f"Raw uncompressed size: {total_size / (1024 * 1024):.2f} MB")
    print(f"Compressed zip size: {zip_size_mb:.2f} MB")

if __name__ == "__main__":
    create_source_zip()
