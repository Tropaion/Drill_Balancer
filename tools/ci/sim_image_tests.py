"""Runs the simulator tests that send the real firmware image
(firmware/build/drillbalancer.bin) and fails when one of them is skipped or
none is found, so the image check cannot pass without running.

They are the test classes and methods in TESTS_DIR/test_*.py decorated with
@unittest.skipUnless(FIRMWARE_IMAGE.exists(), ...), so a new one runs here too.

    python sim_image_tests.py TESTS_DIR          # run them
    python sim_image_tests.py --list TESTS_DIR   # only print their names
"""

import ast
import sys
import unittest
from pathlib import Path


def needs_image(node):
    for dec in node.decorator_list:
        if (isinstance(dec, ast.Call) and ast.unparse(dec.func).endswith("skipUnless")
                and dec.args and "FIRMWARE_IMAGE" in ast.unparse(dec.args[0])):
            return True
    return False


def image_tests(tests_dir):
    names = []
    for src in sorted(tests_dir.glob("test_*.py")):
        tree = ast.parse(src.read_text(), str(src))
        for cls in tree.body:
            if not isinstance(cls, ast.ClassDef):
                continue
            name = f"{src.stem}.{cls.name}"
            if needs_image(cls):
                names.append(name)
                continue
            names += [f"{name}.{fn.name}" for fn in cls.body
                      if isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)) and needs_image(fn)]
    return names


def main(argv):
    list_only = argv[:1] == ["--list"]
    if list_only:
        argv = argv[1:]
    if len(argv) != 1:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    tests_dir = Path(argv[0]).resolve()
    names = image_tests(tests_dir)
    if list_only:
        print("\n".join(names))
        return 0
    if not names:
        print(f"no test in {tests_dir} needs the firmware image", file=sys.stderr)
        return 1
    sys.path.insert(0, str(tests_dir))
    suite = unittest.defaultTestLoader.loadTestsFromNames(names)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if result.skipped:
        for test, why in result.skipped:
            print(f"skipped: {test.id()}: {why}", file=sys.stderr)
        print(f"FAILED: {len(result.skipped)} of the image tests skipped", file=sys.stderr)
        return 1
    if result.testsRun == 0:
        print("FAILED: no image test ran", file=sys.stderr)
        return 1
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
