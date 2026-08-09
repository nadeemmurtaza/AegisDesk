import os
import re

dao_dir = r"shared\database\src\commonMain\kotlin\com\newax\aegis\db\dao"

pattern_delete = re.compile(r'(@Query\(\s*\"(DELETE|UPDATE)[^\"]*\"\s*\)\s*suspend fun\s+[a-zA-Z0-9_]+\([^)]*\))\s*(?!:\s*[a-zA-Z<>]+)', re.MULTILINE)

for root, _, files in os.walk(dao_dir):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            
            new_content = pattern_delete.sub(r'\1: Int', content)
            
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_content)
