"""Scanner: real-time document edge detection + auto-capture reference pipeline."""
from .pipeline import Tracker, Config, order_quad, poly_area, quad_iou  # noqa: F401
from .capture import capture_scan, enhance  # noqa: F401
