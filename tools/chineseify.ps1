# 把所有会显示给用户的英文文案改成中文。
#
# 只改 Java 源码里的字符串字面量，不动逻辑。项目要求"前端不变"，所以所有面向用户的
# 提示都必须由后端以中文给出。

$ErrorActionPreference = 'Stop'
$root = 'D:\旅迹AI（Java）\travel-agent-platform-java\backend\src\main\java'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$map = [ordered]@{
    '"File size must not exceed 10 MiB."' = '"文件大小不能超过 10 MB。"'
    '"Internal Server Error (trace "' = '"服务器内部错误，请稍后重试。（追踪号 "'
    '"Stored file was not found."' = '"找不到已保存的文件。"'
    '"File parsing has not completed."' = '"文件还没有解析完成。"'
    '"Trip does not exist."' = '"旅行项目不存在。"'
    '"File does not exist."' = '"文件不存在。"'

    '"Only pending adjustments can be applied."' = '"只有待确认的草案才能应用。"'
    '"Only pending adjustments can be rejected."' = '"只有待确认的草案才能拒绝。"'
    '"An adjustment can contain at most 48 operations."' = '"一次调整最多包含 48 个操作。"'
    '"The itinerary item is no longer available."' = '"这个行程项已经不存在了。"'
    '"The itinerary operation is incomplete."' = '"行程操作缺少必要信息。"'
    '"The itinerary item does not belong to this trip."' = '"这个行程项不属于当前旅行。"'
    '"The saved place does not belong to this trip."' = '"这个地点不属于当前旅行。"'
    '"A new place is required."' = '"需要提供新地点。"'
    '"The budget amount is invalid."' = '"预算金额不合法。"'
    '"Itinerary dates and times are required."' = '"行程需要日期和起止时间。"'
    '"The itinerary date is outside the trip range."' = '"行程日期不在旅行日期范围内。"'
    '"The itinerary end time is invalid."' = '"行程结束时间不合法。"'
    '"Selected operation index is invalid."' = '"选中的操作序号不合法。"'
    '"The model response does not include an operation list."' = '"模型返回里没有操作列表。"'
    '"Unknown operation kind: "' = '"未知的操作类型："'

    '"DASHSCOPE_API_KEY is not configured"' = '"还没有配置 DASHSCOPE_API_KEY。"'
    '"Multimodal analysis is not configured."' = '"多模态解析还没有配置（缺少 DASHSCOPE_API_KEY）。"'
    '"The vision model returned an invalid analysis result."' = '"视觉模型返回的解析结果不合法。"'
    '"Multimodal analysis failed. Please retry later."' = '"多模态解析失败，请稍后重试。"'
    '"The vision model response must be a JSON object."' = '"视觉模型返回的内容必须是 JSON 对象。"'
    '"Planning job does not exist."' = '"规划任务不存在。"'
    '"This file type cannot be parsed."' = '"这种文件类型无法解析。"'
    '"PDF text extraction failed: "' = '"PDF 文本提取失败："'
    '"Only JPG, JPEG, PNG, WEBP, and PDF files can be parsed."' = '"只能解析 JPG、JPEG、PNG、WEBP 和 PDF 文件。"'
    '"Only JPG, JPEG, PNG, WEBP, and PDF files are supported."' = '"只支持 JPG、JPEG、PNG、WEBP 和 PDF 文件。"'
    '"OCR failed while loading or running the recognition model. "' = '"图片文字识别失败（加载或运行识别模型时出错）。"'
    '"Please retry after restarting the backend."' = '"请重启后端后重试。"'
    '"DASHSCOPE_API_KEY is not set, so uploaded documents cannot be embedded."' = '"没有设置 DASHSCOPE_API_KEY，上传的资料无法建立向量索引。"'
    '"The embedding provider rejected the request: "' = '"向量化服务拒绝了请求："'
    '"The embedding provider returned "' = '"向量化服务返回的向量数量不对："'
    '"A place suggestion is required."' = '"需要先选择要保存的地点。"'
    '"An itinerary suggestion is required."' = '"需要先选择要保存的行程时段。"'
    '"The itinerary date is outside this trip."' = '"行程日期不在当前旅行日期范围内。"'
    '"The itinerary date is outside this trip，"' = '"行程日期不在当前旅行日期范围内。"'
}

$changedFiles = 0
$changedPairs = 0
Get-ChildItem -Path $root -Recurse -Filter *.java | ForEach-Object {
    $path = $_.FullName
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $original = $text
    foreach ($key in $map.Keys) {
        if ($text.Contains($key)) {
            $text = $text.Replace($key, $map[$key])
            $changedPairs++
        }
    }
    if ($text -ne $original) {
        [System.IO.File]::WriteAllText($path, $text, $utf8)
        $changedFiles++
        Write-Host ("updated {0}" -f $_.Name)
    }
}
Write-Host "修改文件 $changedFiles 个，替换 $changedPairs 处"
