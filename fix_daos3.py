import os
import re

dao_dir = r"shared\database\src\commonMain\kotlin\com\newax\aegis\db\dao"

for root, _, files in os.walk(dao_dir):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            
            def replacer(match):
                annotations = match.group(1)
                func_decl = match.group(2)
                # if it already has a return type, don't change
                if re.search(r':\s*[a-zA-Z0-9<>?]+$', func_decl.strip()):
                    return match.group(0)
                return annotations + func_decl + ': Int'
                
            # Match @Query followed by DELETE or UPDATE, then any spacing (including newlines), then suspend fun name(...)
            content = re.sub(r'(@Query\(\s*\"(?:DELETE|UPDATE)[^\"]*\"\s*\)\s*)(suspend fun\s+[a-zA-Z0-9_]+\s*\([^)]*\))(?!\s*:\s*[a-zA-Z0-9<>?]+)', replacer, content, flags=re.MULTILINE)
            
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
