import sys

file_path = r'shared\database\src\commonMain\kotlin\com\newax\aegis\db\AegisDatabase.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix literal string 

content = content.replace("companion object {
        @Volatile var INSTANCE: AegisDatabase? = null
        val get: AegisDatabase get() = INSTANCE ?: error(\"Not initialized\")\r\n", "companion object {\n")
content = content.replace("companion object {
        @Volatile var INSTANCE: AegisDatabase? = null
        val get: AegisDatabase get() = INSTANCE ?: error(\"Not initialized\")\n", "companion object {\n")

if "val get: AegisDatabase get() = INSTANCE ?: error(\"Not initialized\")" not in content:
    # Add it to the companion object
    content = content.replace("companion object {\n", "companion object {\n        @Volatile private var INSTANCE: AegisDatabase? = null\n        val get: AegisDatabase get() = INSTANCE ?: error(\"Not initialized\")\n")

# Remove any duplicate INSTANCE definitions
content = content.replace("        @Volatile private var INSTANCE: AegisDatabase? = null\n          @Volatile private var INSTANCE: AegisDatabase? = null\n", "        @Volatile private var INSTANCE: AegisDatabase? = null\n")

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed!")
