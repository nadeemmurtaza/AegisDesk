import os
import re

dao_dir = r"shared\database\src\commonMain\kotlin\com\newax\aegis\db\dao"

pattern = re.compile(r'(@(Query|Insert|Update|Delete|Transaction)[^\n]*\s+)fun\s+', re.MULTILINE)

for root, _, files in os.walk(dao_dir):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            
            new_content = pattern.sub(r'\1suspend fun ', content)
            
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_content)
