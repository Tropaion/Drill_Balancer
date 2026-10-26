"""The test client's wire codecs, shared rather than copied: tools/ble/db_client.py
(rec_state, rec_ctrl and the stream's constants), written against the C
structs independently of the simulator. It needs only the standard library
(bleak only for the commands that talk to a device). Loaded by path, and
only here: the simulator, its tests and dbdata.synth all take it as
dbsim.wire.dc.
"""

import importlib.util
import sys

from .core import SIM_DIR

DB_CLIENT_PATH = SIM_DIR.parent / "ble" / "db_client.py"


def _load():
    spec = importlib.util.spec_from_file_location("db_client", DB_CLIENT_PATH)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["db_client"] = mod      # its dataclasses look their module up
    spec.loader.exec_module(mod)
    return mod


dc = _load()
