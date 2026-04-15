"""pytest conftest — 이 폴더가 sys.path 에 들어가도록 보장.

`tools/providers` 를 `providers` 로 import 할 수 있게 한다.
runner 도 동일한 방식으로 import 한다.
"""

from __future__ import annotations

import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))
