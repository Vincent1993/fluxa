#!/usr/bin/env python3
"""Package a verified, ephemeral Debug build for an explicitly labelled test release."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

import release


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text())


def main():
    candidate, output = map(Path, sys.argv[1:])
    commit = os.environ['FLUXA_BUILD_COMMIT']
    require(re.fullmatch(r'[a-f0-9]{40}', commit), 'Exact source SHA required')
    require(commit == os.environ['GITHUB_SHA'], 'Workflow/source mismatch')
    run_id = os.environ['GITHUB_RUN_ID']
    require(run_id.isdigit(), 'Invalid run ID')
    apk_dir = candidate / 'fluxa-debug-apk'
    require((apk_dir / 'BUILD_COMMIT.txt').read_text().strip() == commit, 'APK source mismatch')
    original = apk_dir / 'app-debug.apk'
    expected = read_json(apk_dir / 'channel-metadata.json')
    version = release.read_version(Path('version.properties').read_text())
    actual = release.verify_apk(original.resolve(), release.expected(version, 'debug', commit),
                                False, None, os.environ['ANDROID_HOME'])
    for key in ('commit', 'apk_sha256', 'application_id', 'version_name', 'version_code', 'signed'):
        require(expected[key] == actual[key], 'APK metadata mismatch: ' + key)
    verifier = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/apksigner'
    signature = subprocess.check_output([str(verifier), 'verify', '--verbose', '--print-certs',
                                        str(original)], text=True)
    fingerprint = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})', signature)
    require(fingerprint and 'Number of signers: 1' in signature, 'A verified signer is required')
    require('CN=Android Debug' in signature, 'Expected an ordinary Android Debug certificate')

    unit_results = {}
    for channel in ('debug', 'preview', 'stable'):
        matches = list((candidate / ('fluxa-validation-' + channel)).rglob('test-summary.json'))
        require(len(matches) == 1, 'Expected exactly one test summary for ' + channel)
        summary = read_json(matches[0])
        require(summary['commit'] == commit and summary['status'] == 'passed'
                and summary['tests'] > 0 and not any(summary[k] for k in ('failures', 'errors', 'skipped')),
                'Unit tests are not passing: ' + channel)
        unit_results[channel] = summary

    device_results = {}
    screenshots = []
    for api, page_size in (('36', 4096), ('37.0', 16384)):
        reports = candidate / ('fluxa-device-api-' + api)
        require((reports / 'BUILD_COMMIT.txt').read_text().strip() == commit, 'Device source mismatch')
        info = dict(line.split('=', 1) for line in (reports / 'device.txt').read_text().splitlines())
        require(info == {'API': api.split('.')[0], 'PAGE_SIZE': str(page_size)}, 'Device identity mismatch')
        result = {}
        suites = ['upgrade-seed', 'upgrade-verify', 'complete-device-suite',
                  'screenshot-capture', 'screenshot-cleanup', 'reader-interactions-seed',
                  'reader-interactions-verify', 'reader-interactions-cleanup']
        if api == '36':
            suites += ['reader-media', 'reader-triage']
        for name in suites:
            text = (reports / (name + '.log')).read_text()
            passed = re.findall(r'^OK \(([1-9][0-9]*) tests?\)', text, re.MULTILINE)
            require(len(passed) == 1 and not re.search(r'^INSTRUMENTATION_STATUS_CODE: -(3|4)', text, re.MULTILINE),
                    'Device suite did not pass: ' + api + '/' + name)
            result[name] = int(passed[0])
        device_results[api] = result
        for image in sorted(reports.rglob('*.png')):
            require(image.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid screenshot: ' + str(image))
            screenshots.append(str(image.relative_to(candidate)))

    output.mkdir(parents=True, exist_ok=False)
    apk_name = 'fluxa-debug-' + commit[:12] + '.apk'
    shutil.copyfile(original, output / apk_name)
    tag = 'test-' + commit[:12] + '-' + run_id
    metadata = dict(actual, tag=tag, signing_type='ephemeral-android-debug',
                    signing_certificate_sha256=fingerprint[1],
                    cross_build_upgrade_guaranteed=False, unit_tests=unit_results,
                    devices=device_results, screenshots=screenshots,
                    device_scope="scripts/device-ci-classes.txt plus screenshots, reader interactions, and API 36 native media and saved-search probes",
                    actions_url='https://github.com/' + os.environ['GITHUB_REPOSITORY'] + '/actions/runs/' + run_id)
    (output / 'test-release-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    with zipfile.ZipFile(output / 'validation-evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(candidate.rglob('*')):
            if path.is_file() and path.suffix != '.apk':
                archive.write(path, str(path.relative_to(candidate)))
        archive.write(output / 'test-release-metadata.json', 'test-release-metadata.json')
    checksums = ''.join(hashlib.sha256((output / name).read_bytes()).hexdigest() + '  ' + name + '\n'
                        for name in (apk_name, 'validation-evidence.zip'))
    (output / 'SHA256SUMS').write_text(checksums)
    notes = f'''Fluxa 测试预发布：完整本地功能快照与本轮界面改动整合后的 Debug 构建。

源码提交：`{commit}`。验证运行：{metadata['actions_url']}。

下载 `{apk_name}` 安装；包名为 `com.fluxa.app.debug`，与稳定版及 Preview 分开保存数据。
这是 Android 常规临时 Debug 签名（可调试），不是长期签名的正式版。证书 SHA-256：`{fingerprint[1]}`。
不同构建的签名可能不同，不能保证覆盖升级；若系统提示签名冲突，请保留现有安装与数据，不要为安装本测试包直接卸载旧版本。

本次验证：Debug / Preview / Stable 单测分别通过 {unit_results['debug']['tests']} / {unit_results['preview']['tests']} / {unit_results['stable']['tests']} 项；三个渠道构建和 lint 成功。
API 36 与 API 37（16 KB 页）常规设备套件分别通过 {device_results['36']['complete-device-suite']} / {device_results['37.0']['complete-device-suite']} 项，并通过使用同一临时测试证书的覆盖升级检查。
覆盖升级检查使用 CI 内重新构建的基线与候选包，不能证明不同 CI 构建间的签名兼容。
常规设备类清单见 `scripts/device-ci-classes.txt`；专用手工探针不包含在该计数中。设备测试使用离线夹具，未代替真实 NewsBlur / GitHub 用户账号联调、真机后台调度或人工 TalkBack 验收。
API 36 另通过真实图片点击、长按、缩放、平移、关闭及阅读位置保持测试，以及保存搜索应用/删除回归；这些专用测试单独记录在设备日志中。

`validation-evidence.zip` 包含测试报告、设备日志和实际产生的 {len(screenshots)} 张应用截图；截图来自 Android 模拟器。
`SHA256SUMS` 提供 APK 和证据包的 SHA-256，可用 `sha256sum -c SHA256SUMS` 校验。
'''
    (output / 'release-notes.md').write_text(notes)
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
