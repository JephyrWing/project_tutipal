"""PT-85 D1 / AC-27: one shared LangGraph graph, compiled without a checkpointer, langgraph isolated to graph.py."""
import configparser
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANGGRAPH_IMPORT = re.compile(r"^\s*(from|import)\s+langgraph\b", re.MULTILINE)


def test_graph_compiles_without_checkpointer():
    from app.services.agent.graph import build_graph

    graph = build_graph()
    assert graph.checkpointer is None


def test_langgraph_is_imported_only_in_graph_module():
    importers = sorted(
        path.relative_to(ROOT).as_posix()
        for path in (ROOT / "app").rglob("*.py")
        if LANGGRAPH_IMPORT.search(path.read_text(encoding="utf-8"))
    )
    assert importers == ["app/services/agent/graph.py"]


def test_langgraph_is_pinned_in_requirements_and_locks():
    assert "langgraph==1.2.24" in (ROOT / "requirements.txt").read_text(encoding="utf-8").split()
    assert "langgraph==1.2.24" in (ROOT / "requirements.lock.txt").read_text(encoding="utf-8").split()


def test_warnings_stay_errors_with_module_scoped_ignores_only():
    config = configparser.ConfigParser()
    config.read(ROOT / "pytest.ini", encoding="utf-8")
    entries = [line.strip() for line in config["pytest"]["filterwarnings"].splitlines() if line.strip()]
    assert entries[0] == "error"
    for entry in entries[1:]:
        parts = entry.split(":")
        assert parts[0] == "ignore", entry
        assert len(parts) >= 4 and parts[3].strip(), f"ignore must name a module: {entry}"
