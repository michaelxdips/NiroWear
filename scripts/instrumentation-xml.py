"""Convert literal AndroidJUnitRunner status records to JUnit XML; never infer success."""
import re
import sys
import xml.etree.ElementTree as E
from pathlib import Path

path = Path(sys.argv[1])
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
