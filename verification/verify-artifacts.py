"""Verify the original binary, rebuilt source identity, and final patch boundary."""
import hashlib
import json
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
production = root / "EcoItems性能证据交接-20261023/生产输入-EcoItems-2026.27-all.jar"
rebuilt = root / "rebuilt-baseline.jar"
candidate = root / "EcoItems/build/libs/EcoItems-2026.27-1-all.jar"
def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(production) == "9ff79c09592d8962de135fb67ecdc148101811958b3731cd235907a9e8c58918"
with zipfile.ZipFile(production) as old, zipfile.ZipFile(rebuilt) as base, zipfile.ZipFile(candidate) as new:
    assert old.testzip() is None and base.testzip() is None and new.testzip() is None
    classes = [n for n in old.namelist() if n.startswith("com/willfp/ecoitems/") and n.endswith(".class") and "/libreforge/" not in n]
    assert len(classes) == 29
    assert all(old.read(n) == base.read(n) for n in classes)
    changes = [n for n in classes if old.read(n) != new.read(n)]
    assert set(changes) == {"com/willfp/ecoitems/items/ItemUtilsKt.class", "com/willfp/ecoitems/display/ItemsDisplay.class"}
    resources = [n for n in old.namelist() if n.endswith(".yml")]
    for name in resources:
        a, b = (z.read(name).decode("utf-8").replace("\r\n", "\n") for z in (old, new))
        if name == "plugin.yml":
            b = re.sub(r"(?m)^version: 2026\.27-1$", "version: 2026.27", b)
        assert a == b, name
    assert all(not n.startswith("verification/") for n in new.namelist())
    assert set(old.namelist()) == set(new.namelist())
inputs = []
for path in sorted((root / "lab/plugins").glob("*.jar")):
    inputs.append(dict(name=path.name, bytes=path.stat().st_size, sha256=sha(path)))
for path in [Path("C:/Users/Administrator/Desktop/eco系列/shiroha-bundler-26.2-SNAPSHOT (3).jar"), root / "lab/versions/26.2/shiroha-26.2.jar"]:
    inputs.append(dict(name=path.name, bytes=path.stat().st_size, sha256=sha(path)))
result = dict(productionSha256=sha(production), rebuiltSha256=sha(rebuilt), candidateSha256=sha(candidate),
              projectClasses=len(classes), baselineByteIdentical=len(classes), changedClasses=changes,
              resourcesEqualExceptPluginVersion=len(resources), inputs=inputs,
              gradleTests=0, regressionProbe="verification/ItemsProbe.java")
(root / "verification/artifact-verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
