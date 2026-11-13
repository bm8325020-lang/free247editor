#!/usr/bin/env python3
"""Bot de lives: vigia canais da Twitch e do YouTube (com autorização) e clipa os melhores momentos ao vivo.

  python live.py                 vigia todos os canais de live-canais.json
  python live.py --limiar 2.5    mais sensível (mais clipes)
Guia: CLIPES.md, seção "Bot de lives".
"""
import os
import sys
from pathlib import Path

os.environ.setdefault("OPENCV_LOG_LEVEL", "ERROR")
sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))

from clipper.live import main  # noqa: E402

if __name__ == "__main__":
    main()
