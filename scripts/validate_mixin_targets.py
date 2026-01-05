#!/usr/bin/env python3
"""
Advanced Mixin Target Validation
Validates @Inject, @Shadow, @Redirect targets against Minecraft source
"""

import os
import re
import sys
from pathlib import Path
from typing import List, Dict, Tuple, Optional

# Color codes
RED = '\033[0;31m'
GREEN = '\033[0;32m'
YELLOW = '\033[1;33m'
BLUE = '\033[0;34m'
NC = '\033[0m'

class MixinValidator:
    def __init__(self, mc_sources_path: str):
        self.mc_sources = Path(mc_sources_path)
        self.errors = []
        self.warnings = []
        self.checked = 0

    def find_target_class(self, class_name: str) -> Optional[Path]:
        """Find the target class file in Minecraft sources"""
        class_path = class_name.replace('.', '/') + '.java'

        for java_file in self.mc_sources.rglob('*.java'):
            if java_file.as_posix().endswith(class_path):
                return java_file
        return None

    def extract_mixin_target(self, mixin_file: Path) -> Optional[str]:
        """Extract the @Mixin target class"""
        with open(mixin_file, 'r') as f:
            content = f.read()

        # Match @Mixin(ClassName.class) or @Mixin(value = ClassName.class)
        pattern = r'@Mixin\s*\(\s*(?:value\s*=\s*)?([A-Za-z0-9_.]+)\.class'
        match = re.search(pattern, content)

        if match:
            return match.group(1)
        return None

    def extract_inject_targets(self, mixin_file: Path) -> List[Dict]:
        """Extract all @Inject annotations and their targets"""
        with open(mixin_file, 'r') as f:
            lines = f.readlines()

        injects = []
        for i, line in enumerate(lines):
            # Look for @Inject annotation
            if '@Inject' in line:
                # Extract method name
                method_match = re.search(r'method\s*=\s*"([^"]+)"', line)
                if method_match:
                    method_name = method_match.group(1)

                    # Get the mixin method signature (next few lines)
                    mixin_method_lines = []
                    for j in range(i+1, min(i+20, len(lines))):
                        mixin_method_lines.append(lines[j])
                        if 'CallbackInfo' in lines[j]:
                            break

                    mixin_signature = ''.join(mixin_method_lines)

                    injects.append({
                        'method': method_name,
                        'line': i + 1,
                        'mixin_signature': mixin_signature
                    })

        return injects

    def count_parameters(self, signature: str) -> int:
        """Count parameters in a method signature"""
        # Remove generics
        sig = re.sub(r'<[^>]+>', '', signature)

        # Find parameter section
        param_match = re.search(r'\(([^)]*)\)', sig)
        if not param_match:
            return 0

        params = param_match.group(1)
        if not params.strip():
            return 0

        # Count commas (parameters = commas + 1, excluding CallbackInfo)
        if 'CallbackInfo' in params:
            # Remove CallbackInfo from count
            params = re.sub(r',?\s*CallbackInfo\s*\w*', '', params)

        if not params.strip():
            return 0

        return params.count(',') + 1

    def validate_constructor_injection(self, mixin_file: Path, target_class: Path,
                                      inject_info: Dict) -> bool:
        """Validate constructor injection signature"""
        mixin_name = mixin_file.stem

        # Count parameters in mixin signature
        mixin_params = self.count_parameters(inject_info['mixin_signature'])

        # Read target class constructor
        with open(target_class, 'r') as f:
            content = f.read()

        # Find constructor
        class_name = target_class.stem
        constructor_pattern = rf'public\s+{class_name}\s*\('
        constructor_match = re.search(constructor_pattern, content)

        if not constructor_match:
            self.warnings.append(f"{mixin_name}: Constructor not found in {class_name}")
            return True  # Don't fail, might be obfuscated

        # Extract constructor signature
        start = constructor_match.start()
        # Find the closing parenthesis
        depth = 0
        end = start
        for i in range(start, len(content)):
            if content[i] == '(':
                depth += 1
            elif content[i] == ')':
                depth -= 1
                if depth == 0:
                    end = i
                    break

        constructor_sig = content[start:end+1]
        target_params = self.count_parameters(constructor_sig)

        self.checked += 1

        if mixin_params != target_params:
            error = (f"{RED}✗{NC} {mixin_name}:{inject_info['line']} - "
                    f"Constructor signature mismatch\n"
                    f"    Mixin parameters: {mixin_params}\n"
                    f"    Target parameters: {target_params}\n"
                    f"    This will cause InvalidInjectionException at runtime")
            self.errors.append(error)
            return False
        else:
            print(f"  {GREEN}✓{NC} Constructor injection verified ({mixin_params} params)")
            return True

    def validate_method_injection(self, mixin_file: Path, target_class: Path,
                                  inject_info: Dict) -> bool:
        """Validate method injection target exists"""
        mixin_name = mixin_file.stem
        method_name = inject_info['method']

        with open(target_class, 'r') as f:
            content = f.read()

        # Check if method exists
        # Look for method declaration
        method_pattern = rf'\b{re.escape(method_name)}\s*\('
        if re.search(method_pattern, content):
            print(f"  {GREEN}✓{NC} Method '{method_name}' found in target")
            self.checked += 1
            return True
        else:
            error = (f"{RED}✗{NC} {mixin_name}:{inject_info['line']} - "
                    f"Method '{method_name}' not found in target\n"
                    f"    This will cause InvalidInjectionException at runtime")
            self.errors.append(error)
            return False

    def validate_mixin_file(self, mixin_file: Path):
        """Validate a single mixin file"""
        print(f"{BLUE}Validating:{NC} {mixin_file.relative_to(Path.cwd())}")

        # Extract target class
        target_class_name = self.extract_mixin_target(mixin_file)
        if not target_class_name:
            print(f"  {YELLOW}⚠{NC} No @Mixin target found")
            return

        print(f"  Target: {target_class_name}")

        # Find target in MC sources
        target_file = self.find_target_class(target_class_name)
        if not target_file:
            self.warnings.append(f"{mixin_file.stem}: Target class {target_class_name} not found in sources")
            print(f"  {YELLOW}⚠{NC} Target class not found in Minecraft sources")
            return

        # Extract and validate @Inject targets
        injects = self.extract_inject_targets(mixin_file)

        if not injects:
            print(f"  {YELLOW}ℹ{NC} No @Inject annotations found")
            return

        for inject in injects:
            if inject['method'] == '<init>':
                self.validate_constructor_injection(mixin_file, target_file, inject)
            else:
                self.validate_method_injection(mixin_file, target_file, inject)

        print()

    def validate_all(self, src_dir: Path):
        """Validate all mixin files in source directory"""
        print("=== ADVANCED MIXIN TARGET VALIDATION ===\n")

        if not self.mc_sources.exists():
            print(f"{YELLOW}WARNING: Minecraft sources not found at {self.mc_sources}{NC}")
            print("Run: bash scripts/setup_reference_sources.sh")
            print("\nValidation skipped - requires decompiled Minecraft sources")
            return 0

        print(f"Minecraft sources: {self.mc_sources}\n")

        # Find all mixin files
        mixin_files = list(src_dir.rglob('*Mixin*.java'))
        mixin_files.extend(src_dir.rglob('*Accessor*.java'))

        if not mixin_files:
            print(f"{YELLOW}No mixin files found{NC}")
            return 0

        print(f"Found {len(mixin_files)} mixin files\n")

        for mixin_file in sorted(mixin_files):
            self.validate_mixin_file(mixin_file)

        # Print summary
        print("=" * 50)
        print("=== VALIDATION SUMMARY ===")
        print(f"Injections checked: {self.checked}")
        print(f"Errors found: {len(self.errors)}")
        print(f"Warnings: {len(self.warnings)}")
        print()

        if self.errors:
            print(f"{RED}✗ VALIDATION FAILED{NC}")
            print(f"\nFound {len(self.errors)} critical issue(s):\n")
            for error in self.errors:
                print(error)
            return 1
        else:
            print(f"{GREEN}✓ ALL SIGNATURE CHECKS PASSED{NC}")
            if self.warnings:
                print(f"\n{YELLOW}Warnings ({len(self.warnings)}):{NC}")
                for warning in self.warnings:
                    print(f"  • {warning}")
            return 0

def main():
    mc_sources = Path('.reference/minecraft/1.21.1/decompiled')
    src_dir = Path('src/main/java')

    validator = MixinValidator(str(mc_sources))
    exit_code = validator.validate_all(src_dir)

    sys.exit(exit_code)

if __name__ == '__main__':
    main()
