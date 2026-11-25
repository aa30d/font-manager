"""Print JUnit XML failures as GitHub annotations, so CI logs stay optional."""
from pathlib import Path
import xml.etree.ElementTree as ET


def escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> None:
    results = Path("app/build/test-results")
    if not results.exists():
        return
    for report in results.rglob("*.xml"):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for failure in case.findall("failure"):
                message = failure.get("message") or failure.text or ""
                name = f"{case.get('classname', 'test')} > {case.get('name', 'test')}"
                print(f"::error file=app/src/test::{escape(name)}: {escape(message[:1200])}")


if __name__ == "__main__":
    main()
