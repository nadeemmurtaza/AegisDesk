import os
import re

dao_dir = r"shared\database\src\commonMain\kotlin\com\newax\aegis\db\dao"

for root, _, files in os.walk(dao_dir):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
            
            # Find all fun that don't have suspend before them.
            # Since all fun in a Dao are Room methods (interface methods), we can just replace '    fun ' with '    suspend fun ' 
            # EXCEPT if they return Flow.
            
            def replacer(match):
                if 'Flow<' in match.group(0):
                    return match.group(0)
                return match.group(1) + 'suspend fun ' + match.group(2)
                
            content = re.sub(r'(^[ \t]+)fun (.*)', replacer, content, flags=re.MULTILINE)
            
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
