#!/usr/bin/env python3
"""Summarize actual JUnit test cases without counting nested suites twice."""
import argparse
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('results')
parser.add_argument('--output', required=True)
args = parser.parse_args()
files = sorted(Path(args.results).rglob('TEST-*.xml'))
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for report in files:
    for case in ET.parse(report).getroot().iter('testcase'):
        counts['tests'] += 1
        for kind, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
            counts[kind] += case.find(tag) is not None
status = 'not-run' if not counts['tests'] else ('failed' if counts['failures'] or counts['errors'] or counts['skipped'] else 'passed')
result = dict(commit=os.environ.get('FLUXA_BUILD_COMMIT'), status=status, reports=len(files), **counts)
out = Path(args.output)
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, indent=2) + '\n')
print('JUnit summary: ' + json.dumps(result))
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('\nJUnit summary: `' + json.dumps(result) + '`\n')
# Missing, failed, errored, or skipped tests must never turn a job green.
raise SystemExit(0 if status == 'passed' else 1)
