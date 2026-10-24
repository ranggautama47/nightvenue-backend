"""conftest.py — makes 'app' importable when running pytest from ml-service root."""
import sys
from pathlib import Path

# Add ml-service root to sys.path so `from app.main import app` resolves
sys.path.insert(0, str(Path(__file__).parent))
