"""Convert literal AndroidJUnitRunner status records to JUnit XML; never infer success."""
import re
import sys
import xml.etree.ElementTree as E
from pathlib import Path

argv = sys.argv[1:]
expected = None
if "--smoke-classes" in argv:
    index = argv.index("--smoke-classes")
    expected = [name for name in argv[index + 1].split(",") if name]
    del argv[index:index + 2]
path = Path(argv[0])
raw = path.read_bytes()
text = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
cases = []
state = {}
for line in text.splitlines():
    match = re.match(r"INSTRUMENTATION_STATUS: (class|test|stack)=(.*)", line)
    if match:
        state[match[1]] = match[2]
    match = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
    if match and int(match[1]) <= 0 and "test" in state:
        cases.append((state.copy(), int(match[1])))
        state = {}
if expected is None:
    assert cases, "No terminal test records"
suite = E.Element("testsuite", tests=str(len(cases)), failures=str(sum(code in (-1, -2) for _, code in cases)),
                  errors="0", skipped=str(sum(code in (-3, -4) for _, code in cases)))
for case, code in cases:
    node = E.SubElement(suite, "testcase", classname=case.get("class", ""), name=case["test"])
    if code in (-1, -2):
        E.SubElement(node, "failure").text = case.get("stack", "See complete literal instrumentation log")
    elif code in (-3, -4):
        E.SubElement(node, "skipped").text = case.get("stack", "Skipped by runner")
E.ElementTree(suite).write(path.with_suffix(".xml"), encoding="utf-8", xml_declaration=True)
print(suite.attrib)

# Opt-in gate: the XML is written first, then the run is judged against the classes the workflow meant
# to execute. Any gap — a class that never ran, a duplicated record, a non-pass code, a missing final
# INSTRUMENTATION_CODE: -1 or an OK(n) that disagrees with the records — is a failure, never a pass.
if expected is not None:
    problems = []
    ran = {case.get("class", "") for case, _ in cases}
    if ran != set(expected):
        problems.append("classes ran %s != expected %s" % (sorted(ran), sorted(set(expected))))
    pairs = [(case.get("class", ""), case["test"]) for case, _ in cases]
    if len(pairs) != len(set(pairs)):
        problems.append("duplicate (class,test) records present")
    failed = sorted({code for _, code in cases if code != 0})
    if failed:
        problems.append("non-pass status codes: %s" % failed)
    codes = [int(value) for value in re.findall(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)", text)]
    totals = [int(value) for value in re.findall(r"(?m)^OK \((\d+) tests?\)", text)]
    if codes[-1:] != [-1]:
        problems.append("final INSTRUMENTATION_CODE missing or not -1: %s" % codes)
    if not totals:
        problems.append("OK (n tests) summary missing")
    elif totals[-1] != len(cases):
        problems.append("OK (%d tests) != %d recorded tests" % (totals[-1], len(cases)))
    for problem in problems:
        print("SMOKE_CLASSES_FAIL: " + problem)
    sys.exit(1 if problems else 0)
