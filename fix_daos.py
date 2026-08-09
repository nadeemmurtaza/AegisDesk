import os
import re

dao_dir = r"shared\database\src\commonMain\kotlin\com\newax\aegis\db\dao"

def add_suspend(match):
    annotations = match.group(1)
    func_sig = match.group(2)
    if 'suspend ' in annotations or 'suspend ' in func_sig or 'Flow<' in func_sig:
        return match.group(0)
    return annotations + 'suspend ' + func_sig

def fix_return_type(match):
    annotations = match.group(1)
    func_decl = match.group(2)
    return annotations + func_decl + ': Int'

for root, _, files in os.walk(dao_dir):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            
            content = re.sub(r'((?:@[A-Za-z0-9_]+(?:\([^)]*\))?\s*)+)(fun\s+[a-zA-Z0-9_]+\s*\([^)]*\)(?:\s*:\s*[a-zA-Z0-9<>?]+)?)', add_suspend, content)
            
            content = re.sub(r'(@Query\(\s*\"(?:DELETE|UPDATE)[^\"]*\"\s*\)\s*)(suspend fun\s+[a-zA-Z0-9_]+\s*\([^)]*\))(?!\s*:\s*[a-zA-Z0-9<>?]+)', fix_return_type, content)
            
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
