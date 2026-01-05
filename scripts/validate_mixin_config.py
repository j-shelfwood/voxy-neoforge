#!/usr/bin/env python3
"""
Comprehensive Mixin Configuration Validation
Validates mixin registration, exclusions, remap flags, and potential conflicts
"""

import json
import re
import os
from pathlib import Path
from typing import List, Dict, Set, Tuple

# Color codes
RED = '\033[0;31m'
GREEN = '\033[0;32m'
YELLOW = '\033[1;33m'
BLUE = '\033[0;34m'
NC = '\033[0m'

class MixinConfigValidator:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.passed_checks = []
        
    def validate_all(self):
        """Run all validation checks"""
        print(f"{BLUE}=== COMPREHENSIVE MIXIN CONFIGURATION VALIDATION ==={NC}\n")
        
        # Check 1: Registration coverage
        self.check_registration_coverage()
        
        # Check 2: Exclusion alignment
        self.check_exclusion_alignment()
        
        # Check 3: Remap flag validation
        self.check_remap_flags()
        
        # Check 4: JSON schema validation
        self.check_json_schema()
        
        # Check 5: Package naming conventions
        self.check_package_conventions()
        
        # Print summary
        self.print_summary()
        
        return len(self.errors)
    
    def check_registration_coverage(self):
        """Validate mixin registration vs source files"""
        print(f"{BLUE}[1] Mixin Registration Coverage{NC}")
        
        # Read mixin configs
        with open('src/main/resources/client.voxy.mixins.json') as f:
            client_config = json.load(f)
            client_mixins = set(client_config.get('client', []))
        
        with open('src/main/resources/common.voxy.mixins.json') as f:
            common_config = json.load(f)
            common_mixins = set(common_config.get('mixins', []))
        
        # Find all mixin source files
        client_files = set()
        common_files = set()
        
        for root, dirs, files in os.walk('src/main/java'):
            for file in files:
                if not ('Mixin' in file or 'Accessor' in file):
                    continue
                    
                rel_path = os.path.join(root, file).replace('src/main/java/', '')
                
                if 'me/cortex/voxy/client/mixin' in rel_path:
                    mixin_name = rel_path.replace('me/cortex/voxy/client/mixin/', '').replace('.java', '').replace('/', '.')
                    client_files.add(mixin_name)
                elif 'me/cortex/voxy/commonImpl/mixin' in rel_path:
                    mixin_name = rel_path.replace('me/cortex/voxy/commonImpl/mixin/', '').replace('.java', '').replace('/', '.')
                    common_files.add(mixin_name)
        
        # Check for unregistered active mixins (not excluded)
        unregistered = []
        for mixin in client_files:
            if mixin not in client_mixins and not self.is_intentionally_excluded(mixin, 'client'):
                unregistered.append(('client', mixin))
        
        for mixin in common_files:
            if mixin not in common_mixins and not self.is_intentionally_excluded(mixin, 'common'):
                unregistered.append(('common', mixin))
        
        if unregistered:
            self.warnings.append(f"Found {len(unregistered)} unregistered mixins that may need attention")
            print(f"  {YELLOW}⚠{NC} {len(unregistered)} unregistered mixins")
        else:
            self.passed_checks.append("All active mixins are properly registered")
            print(f"  {GREEN}✓{NC} All active mixins registered")
        
        print()
    
    def check_exclusion_alignment(self):
        """Check that removed mixins are properly excluded from compilation"""
        print(f"{BLUE}[2] Build Exclusion Alignment{NC}")
        
        # Parse build.gradle exclusions
        with open('build.gradle') as f:
            content = f.read()
            exclusions = re.findall(r"exclude\s+'([^']+)'", content)
        
        # Check if MixinBlockableEventLoop is excluded
        blockable_excluded = any('MixinBlockableEventLoop' in exc for exc in exclusions)
        
        if not blockable_excluded:
            self.errors.append("MixinBlockableEventLoop not excluded but removed from mixin config")
            print(f"  {RED}✗{NC} MixinBlockableEventLoop should be excluded (removed in Issue #2)")
        else:
            self.passed_checks.append("MixinBlockableEventLoop properly excluded")
            print(f"  {GREEN}✓{NC} MixinBlockableEventLoop excluded")
        
        print()
    
    def check_remap_flags(self):
        """Validate remap=false is used correctly for non-Minecraft classes"""
        print(f"{BLUE}[3] Remap Flag Validation{NC}")
        
        remap_issues = []
        
        for root, dirs, files in os.walk('src/main/java/me/cortex/voxy'):
            for file in files:
                if not ('Mixin' in file or 'Accessor' in file):
                    continue
                
                filepath = os.path.join(root, file)
                with open(filepath) as f:
                    content = f.read()
                
                # Extract @Mixin annotation
                mixin_match = re.search(r'@Mixin\(([^)]+)\)', content)
                if not mixin_match:
                    continue
                
                mixin_annotation = mixin_match.group(1)
                
                # Check if targeting non-Minecraft class
                has_remap_false = 'remap' in content and 'false' in mixin_annotation
                
                # RenderSystem, Sodium, Nvidium classes should have remap=false
                if 'RenderSystem.class' in mixin_annotation:
                    if not has_remap_false:
                        remap_issues.append((file, 'RenderSystem', 'missing remap=false'))
                
                # Check @Inject with remap flags
                for match in re.finditer(r'@Inject\([^)]+remap\s*=\s*false[^)]*\)', content):
                    # RenderSystem.initRenderer should have remap=false
                    if 'MixinRenderSystem' in file:
                        self.passed_checks.append(f"{file}: Correct remap=false for non-MC method")
        
        if remap_issues:
            for file, target, issue in remap_issues:
                self.warnings.append(f"{file}: {target} - {issue}")
                print(f"  {YELLOW}⚠{NC} {file}: {issue}")
        else:
            print(f"  {GREEN}✓{NC} Remap flags correctly applied")
        
        print()
    
    def check_json_schema(self):
        """Validate mixin JSON files have correct structure"""
        print(f"{BLUE}[4] JSON Schema Validation{NC}")
        
        json_files = [
            'src/main/resources/client.voxy.mixins.json',
            'src/main/resources/common.voxy.mixins.json'
        ]
        
        for json_file in json_files:
            try:
                with open(json_file) as f:
                    config = json.load(f)
                
                # Check required fields
                required_fields = ['required', 'package', 'compatibilityLevel']
                missing_fields = [f for f in required_fields if f not in config]
                
                if missing_fields:
                    self.errors.append(f"{json_file}: Missing required fields: {missing_fields}")
                    print(f"  {RED}✗{NC} {os.path.basename(json_file)}: Missing fields")
                else:
                    print(f"  {GREEN}✓{NC} {os.path.basename(json_file)}: Valid schema")
                
                # Check for duplicates
                if 'client' in config:
                    mixins = config['client']
                    if len(mixins) != len(set(mixins)):
                        self.errors.append(f"{json_file}: Duplicate mixin entries")
                        print(f"  {RED}✗{NC} Duplicate entries found")
                
            except json.JSONDecodeError as e:
                self.errors.append(f"{json_file}: JSON parse error - {e}")
                print(f"  {RED}✗{NC} JSON parse error")
        
        print()
    
    def check_package_conventions(self):
        """Check that mixin package naming follows conventions"""
        print(f"{BLUE}[5] Package Naming Conventions{NC}")
        
        naming_issues = []
        
        for root, dirs, files in os.walk('src/main/java/me/cortex/voxy'):
            for file in files:
                if not file.startswith('Mixin') and not file.startswith('Accessor'):
                    if 'mixin' in root.lower():
                        naming_issues.append(f"{os.path.join(root, file)}: Should start with Mixin or Accessor")
        
        if naming_issues:
            for issue in naming_issues[:5]:  # Show first 5
                self.warnings.append(issue)
                print(f"  {YELLOW}⚠{NC} {issue}")
        else:
            self.passed_checks.append("All mixin files follow naming conventions")
            print(f"  {GREEN}✓{NC} Naming conventions followed")
        
        print()
    
    def is_intentionally_excluded(self, mixin: str, side: str) -> bool:
        """Check if mixin is in an intentionally excluded integration"""
        excluded_prefixes = ['iris.', 'flashback.', 'nvidium.', 'chunky.']
        excluded_specific = ['minecraft.MixinDebugScreenEntryList', 'minecraft.MixinFogRenderer', 
                            'minecraft.MixinGlDebug', 'sodium.MixinVideoSettingsScreen',
                            'minecraft.MixinBlockableEventLoop']
        
        return any(mixin.startswith(p) for p in excluded_prefixes) or mixin in excluded_specific
    
    def print_summary(self):
        """Print validation summary"""
        print("=" * 60)
        print(f"{BLUE}=== VALIDATION SUMMARY ==={NC}")
        print(f"Checks passed: {GREEN}{len(self.passed_checks)}{NC}")
        print(f"Warnings: {YELLOW}{len(self.warnings)}{NC}")
        print(f"Errors: {RED}{len(self.errors)}{NC}")
        
        if self.errors:
            print(f"\n{RED}✗ VALIDATION FAILED{NC}")
            print("\nErrors:")
            for error in self.errors:
                print(f"  • {error}")
        elif self.warnings:
            print(f"\n{YELLOW}⚠ VALIDATION PASSED WITH WARNINGS{NC}")
        else:
            print(f"\n{GREEN}✓ ALL VALIDATION CHECKS PASSED{NC}")

def main():
    validator = MixinConfigValidator()
    exit_code = validator.validate_all()
    return exit_code

if __name__ == '__main__':
    import sys
    sys.exit(main())
