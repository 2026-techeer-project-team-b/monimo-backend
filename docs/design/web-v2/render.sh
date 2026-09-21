#!/bin/zsh
# 아트보드를 헤드리스 크롬으로 렌더해 shots/<Name>.png 저장 (x-dc 런타임 없이 정적 미리보기)
cd "$(dirname "$0")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for f in canvas/project/*.dc.html; do
  n=$(basename "$f" .dc.html)
  h=$(python3 -c "import re,sys;s=open('$f',encoding='utf-8').read();m=re.search(r'\"height\":(\d+)\}',s);print(m.group(1) if m else 960)")
  python3 - "$f" "render/$n.html" <<'PY'
import sys,re
src=open(sys.argv[1],encoding='utf-8').read()
src=src.replace('<script src="./support.js"></script>','')
src=re.sub(r'<script type="text/x-dc"[\s\S]*?</script>','',src)
src=src.replace('<x-dc>','<div>').replace('</x-dc>','</div>').replace('<helmet>','').replace('</helmet>','')
src=src.replace('{{accent}}','#3452D6')
open(sys.argv[2],'w',encoding='utf-8').write(src)
PY
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --window-size=1440,$h --screenshot="shots/$n.png" "file://$PWD/render/$n.html" >/dev/null 2>&1
  echo "$n $h $(test -s shots/$n.png && echo ok || echo FAIL)"
done
