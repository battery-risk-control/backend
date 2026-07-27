# GDELT 수집 -> 트리아지 -> 크롤링 -> LLM 정보추출 -> 심각도 스코어링까지 이어지는
# 실시간 리스크 파이프라인(realtime_risk_pipeline.py) 실행 래퍼.
# 작업 스케줄러(Realtime_Risk_Pipeline_15min)가 15분마다 이 스크립트를 호출한다.
# 역할: 프로젝트 루트로 이동 -> python realtime_risk_pipeline.py 실행 ->
# stdout/stderr를 날짜별 로그 파일(logs/realtime_YYYYMMDD.log)에 append.
#
# 주의(로컬 전용): 이 자동화는 이 컴퓨터 하나의 로컬 데이터(cleaned_labeled_articles.csv,
# processed_events.db 등)를 기준으로 동작한다. 이 파일을 pull 받았다고 자동으로 실행되는
# 건 아니지만(작업 스케줄러 등록은 OS 로컬 설정이라 git에 포함되지 않음), 팀원이 각자
# 이 스크립트로 자기 컴퓨터에도 스케줄러를 등록하면 같은 이벤트를 중복으로 크롤링/LLM
# 호출하게 되어 비용이 배로 들고 데이터가 여러 로컬로 쪼개진다. 담당자 1인만 등록할 것.
#
# Start-Process + -RedirectStandard* 사용 이유: PowerShell 5.1에서 네이티브 실행파일에
# `*>>`/`2>&1`로 스트림을 합치면 stderr 각 줄이 NativeCommandError로 감싸져 잘리고
# $ErrorActionPreference="Stop"과 만나면 트레이스백이 중간에 끊긴다. Start-Process로
# 표준출력/에러를 파일로 직접 리다이렉트하면 이 문제를 피할 수 있다.

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $ProjectRoot "logs"
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
}

$LogFile = Join-Path $LogDir ("realtime_{0}.log" -f (Get-Date -Format "yyyyMMdd"))
$Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Add-Content -Path $LogFile -Encoding utf8 -Value "===== [$Timestamp] run_realtime_pipeline.ps1 시작 ====="

$StdOutFile = Join-Path $LogDir "_stdout_tmp.log"
$StdErrFile = Join-Path $LogDir "_stderr_tmp.log"

$env:PYTHONIOENCODING = "utf-8"

$proc = Start-Process -FilePath "python" -ArgumentList @("realtime_risk_pipeline.py") `
    -WorkingDirectory $ProjectRoot -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $StdOutFile -RedirectStandardError $StdErrFile
$ExitCode = $proc.ExitCode

if (Test-Path $StdOutFile) {
    Get-Content -Path $StdOutFile -Encoding utf8 | Add-Content -Path $LogFile -Encoding utf8
    Remove-Item $StdOutFile -Force
}
if (Test-Path $StdErrFile) {
    Get-Content -Path $StdErrFile -Encoding utf8 | Add-Content -Path $LogFile -Encoding utf8
    Remove-Item $StdErrFile -Force
}

$EndTimestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
if ($ExitCode -ne 0) {
    Add-Content -Path $LogFile -Encoding utf8 -Value "⚠️ [$EndTimestamp] 종료 코드 $ExitCode (실패) — 다음 15분 주기에 재시도됨"
} else {
    Add-Content -Path $LogFile -Encoding utf8 -Value "===== [$EndTimestamp] run_realtime_pipeline.ps1 정상 종료 ====="
}

exit $ExitCode
