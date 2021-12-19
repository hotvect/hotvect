import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path
from typing import List

import time


def runshell(command, verbose=True):
    if verbose:
        print(f"Running {' '.join(command)}")

    p = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout = p.stdout.decode('utf-8')
    stderr = p.stderr.decode('utf-8')
    ret_code = p.returncode

    if verbose:
        print(f'return_code:{ret_code}\n')
        print(f"stdout:{p.stdout.decode('utf-8')}\n")
        print(f"stderr:{p.stderr.decode('utf-8')}\n")

    if p.returncode != 0:
        raise ValueError(f'ret:{ret_code}, stdout:{stdout}, stderr:{stderr}')

    return {
        'return_code': ret_code,
        'stdout': stdout,
        'stderr': stderr
    }


def trydelete(file):
    try:
        os.remove(file)
        return True
    except:
        try:
            shutil.rmtree(file)
            return True
        except OSError as e:
            return False


def read_json(file):
    with open(file) as f:
        return json.load(f)


def beep():
    for _i in range(30):
        time.sleep(0.05)
    os.system("printf '\a'")


def to_zip_archive(sources: List[str], dest: str, compress_type=zipfile.ZIP_DEFLATED):
    with zipfile.ZipFile(dest, 'w') as zipF:
        for file in sources:
            zipF.write(file, arcname=os.path.basename(file), compress_type=compress_type)


def clean_dir(d: str):
    trydelete(d)
    Path(d).mkdir(parents=True, exist_ok=True)
    return


def prepare_dir(f: str):
    p = Path(f)
    if p.is_dir():
        clean_dir(f)
    else:
        clean_dir(str(p.parent))


def ensure_file_exists(file: str):
    if not os.path.isfile(file):
        raise ValueError(f'{file} not found')


def ensure_dir_exists(directory: str):
    if not os.path.isdir(directory):
        raise ValueError(f'{directory} not found')
