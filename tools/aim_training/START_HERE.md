# 在另一台电脑开始训练

1. 解压训练包。安装 64 位 Python 3.12，NVIDIA 驱动保持可用。
2. **单独复制原始 legit.csv**，例如放到 `D:\AimData\legit.csv`。训练包不包含这个 4.42 GB 文件。
3. 在训练包目录打开 PowerShell，依次执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1 -Device cuda
.\.venv\Scripts\python.exe prepare.py 'D:\AimData\legit.csv' data
.\.venv\Scripts\python.exe train.py train data runs\gru48 --device cuda --epochs 20
```

将第二条命令的 CSV 路径换成你实际放置的位置。安装脚本会检测真实 GPU、显存，并运行一次 GPU GRU 前向/反向检查。

**预计时间**：首次环境安装预留 5–20 分钟，取决于下载速度；准备好环境后，预处理约 2–5 分钟，首个 20 轮实验预留 5–15 分钟。GPU 时间为估计；本机 CPU 全量预处理实测 112.5 秒，完整一轮训练加验证实测 12.72 秒。

**拿回哪些结果**：训练结束后复制整个 `runs\gru48` 文件夹，重点是 `best.pt`、`validation.json`、`history.jsonl`、`model-metadata.json` 和 `environment.json`。

如训练中断，保持配置不变，从最近完整轮次继续：

```powershell
.\.venv\Scripts\python.exe train.py train data runs\gru48 --device cuda --epochs 20 --resume
```

详细参数、CPU 模式、测试集评估和输入格式见 [README.md](README.md)。当前模型只用于离线实验，未接入客户端。
