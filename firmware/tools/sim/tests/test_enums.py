"""The simulator's hand-numbered enums against the firmware headers."""

import re
import unittest
from pathlib import Path

from simtest import SIM_DIR, C

COMPONENTS = SIM_DIR.parent.parent / "components"


def header_enum(header, typedef_name):
    """{name: value} of `typedef enum { ... } typedef_name;` (values count up from 0)."""
    text = (COMPONENTS / header).read_text()
    body = re.search(r"typedef enum \{([^}]*)\}\s*" + typedef_name + ";", text).group(1)
    body = re.sub(r"//[^\n]*", "", body)
    out, nxt = {}, 0
    for item in (i.strip() for i in body.split(",")):
        if not item:
            continue
        name, _, val = (p.strip() for p in item.partition("="))
        nxt = int(val, 0) if val else nxt
        out[name] = nxt
        nxt += 1
    return out


class EnumTest(unittest.TestCase):
    def check(self, header, typedef_name, prefix, skip=()):
        want = header_enum(header, typedef_name)
        self.assertGreater(len(want), 3, want)
        have = {n: v for n, v in vars(C).items() if n.startswith(prefix) and isinstance(v, int)}
        for name, value in want.items():
            if name not in skip:
                self.assertEqual(have.get(name), value, f"{name} in dbsim/core.py")
        have.pop("APP_CMD_ARG_FROM_BLE", None)   # a #define, checked on its own
        self.assertEqual(set(have) - set(want), set(), "constants the firmware does not have")

    def test_app_cmd_ids(self):
        self.check("app_core/include/app_state.h", "app_cmd_id_t", "APP_CMD_")
        text = (COMPONENTS / "app_core/include/app_state.h").read_text()
        arg = re.search(r"#define APP_CMD_ARG_FROM_BLE\s+(\d+)", text).group(1)
        self.assertEqual(C.APP_CMD_ARG_FROM_BLE, int(arg))

    def test_level_modes(self):
        self.check("level/include/level.h", "level_mode_t", "LEVEL_MODE_", skip=("LEVEL_MODE_COUNT",))
        self.assertEqual(C.LEVEL_MODE_ANGLE, 2)


if __name__ == "__main__":
    unittest.main()
