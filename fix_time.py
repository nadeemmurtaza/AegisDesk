import os
import re

dir_path = r'shared\database\src\commonMain\kotlin\com\newax\aegis\db'
for root, dirs, files in os.walk(dir_path):
    for file in files:
        if file.endswith('.kt'):
            file_path = os.path.join(root, file)
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
            if 'System.currentTimeMillis()' in content:
                new_content = content.replace('System.currentTimeMillis()', 'currentTimeMillis()')
                if 'package com.newax.aegis.db.entity' in new_content and 'import com.newax.aegis.db.currentTimeMillis' not in new_content:
                    # Insert import after package declaration
                    new_content = re.sub(
                        r'(package com\.newax\.aegis\.db\.entity\n)',
                        r'\1\nimport com.newax.aegis.db.currentTimeMillis\n',
                        new_content
                    )
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f'Updated {file_path}')
