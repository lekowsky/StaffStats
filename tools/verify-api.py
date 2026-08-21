#!/usr/bin/env python3
"""
StaffStats — weryfikator deskryptorów API po kompilacji (poziom bajtkodu).

Dlaczego: plugin kompiluje się bez pełnego JARa PaperAPI, więc każda literówka
lub wymyślona klasa w definicjach kompilacyjnych (stuby) trafia do bajtkodu
jako zły deskryptor i wybucha dopiero NA SERWERZE jako NoSuchMethodError
(np. awaria 1.6.2: org/bukkit/plugin/PluginDescription zamiast PluginDescriptionFile).

Jak działa: skanuje stałą pulę wszystkich klas pl/kadrastats/** w JARze,
wyciąga KAŻDĄ referencję do typu org/bukkit / io/papermc / net/luckperms
i porównuje z api-allowlist.txt (lista 972 PRAWDZIWYCH klas bukkit API
z migawki źródeł + klasy Paper/LuckPerms używane przez plugin).
Nieznany typ = FAIL budowa.

Użycie:  python3 tools/verify-api.py builds/StaffStats-X.Y.Z.jar
Exit:    0 = OK, 1 = wykryto nieznane typy.
"""
import re
import sys
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ALLOWLIST_FILE = os.path.join(HERE, "api-allowlist.txt")

# referencje typów w puli stałej (pola, metody, sygnatury, instanceof, invoke...)
# UWAGA: klasa znaków musi zawierać '/' — inaczej nie dopasuje klas wielopakietowych
TYPE_REF = re.compile(rb"L((?:org/bukkit|io/papermc|net/luckperms)/[/A-Za-z0-9_$]+);")
# klasy do sprawdzania — tylko nasze (stuby/shade nas nie interesują)
OUR_CLASSES = re.compile(r"^pl/kadrastats/.*\.class$")


def load_allowlist():
    allow = set()
    for line in open(ALLOWLIST_FILE, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        allow.add(line)
    return allow


def main():
    if len(sys.argv) < 2:
        print("użycie: verify-api.py <StaffStats.jar>")
        return 1
    jar = sys.argv[1]
    allow = load_allowlist()

    def known(internal_name: str) -> bool:
        if internal_name in allow:
            return True
        # klasy zagnieżdżone: Bazowa$Zagniezdzona → dopuszczamy, jeśli Bazowa jest znana
        outer = internal_name.split("$")[0]
        return outer in allow

    unknown = {}
    checked = 0
    with zipfile.ZipFile(jar) as z:
        for entry in z.namelist():
            if not OUR_CLASSES.match(entry):
                continue
            checked += 1
            data = z.read(entry)
            for match in TYPE_REF.finditer(data):
                name = match.group(1).decode("utf-8", "replace")
                if not known(name):
                    unknown.setdefault(name, set()).add(entry)

    print(f"Sprawdzone klasy: {checked}, dozwolone typy API: {len(allow)}")
    if unknown:
        print("\n✗ NIEZNANE TYPY API (błąd builda!):")
        for name, where in sorted(unknown.items()):
            print(f"  {name}   ← {', '.join(sorted(where))}")
        print("\nNapraw definicje kompilacyjne lub dopisz PRAWDZIWĄ klasę do api-allowlist.txt")
        return 1
    print("✓ Wszystkie referencje org.bukkit / io.papermc / net.luckperms są prawdziwymi klasami API")
    return 0


if __name__ == "__main__":
    sys.exit(main())
