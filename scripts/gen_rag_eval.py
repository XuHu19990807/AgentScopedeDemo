#!/usr/bin/env python3
"""生成多格式 RAG 评测语料与数据集（3 域 × 6 格式 = 18 文件，120 题）。

用法（需要 python-docx / openpyxl / python-pptx）：

    python scripts/gen_rag_eval.py

输出：
    src/test/resources/rag-eval/corpus/<file>
    src/test/resources/rag-eval/dataset.jsonl

语料全部为虚构数据，答案关键词在域内唯一，用于自动计算 Recall@k / MRR / nDCG。
"""
from __future__ import annotations

import json
import random
from pathlib import Path

from docx import Document
from docx.shared import Pt
from openpyxl import Workbook
from pptx import Presentation

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "src" / "test" / "resources" / "rag-eval" / "corpus"
DATASET = ROOT / "src" / "test" / "resources" / "rag-eval" / "dataset.jsonl"

DOMAINS = [
    {
        "key": "network",
        "name": "华东区光纤专线",
        "city": "上海",
        "peer": "南京",
        "bandwidth": 4000,
        "prev_bandwidth": 2000,
        "budget": 168,
        "model": "S6730-H48X6C",
        "core": "NE8000-X8",
        "modules": 8,
        "module_price": 0.42,
        "device_count": 12,
        "owner": "张伟",
        "date": "2025-06-30",
        "window": "每周六 00:00-06:00",
        "sla": "99.95%",
        "latency": 23,
        "jitter": 3,
        "loss": "0.01%",
        "peak": 3800,
        "avg": 2650,
        "peak_time": "2025-05-18 20:15",
        "mtu": 9000,
        "asn": "64512",
        "nodes": 6,
        "milestone1": "2025-04-15",
        "milestone2": "2025-06-10",
        "advance": "30%",
        "warranty": "36 个月",
        "spare": 4,
        "review_date": "2025-03-20",
        "decision": "批准立项",
        "risk": "中风险",
        "phase2": 96,
        "drill": 2,
        "rollback": 45,
        "concurrent": 8000,
    },
    {
        "key": "ops",
        "name": "华南数据中心互联",
        "city": "广州",
        "peer": "深圳",
        "bandwidth": 1000,
        "prev_bandwidth": 500,
        "budget": 92,
        "model": "S5735-L48T4X",
        "core": "NE40E-M2K",
        "modules": 16,
        "module_price": 0.18,
        "device_count": 24,
        "owner": "李静",
        "date": "2025-09-15",
        "window": "每周日 01:00-05:00",
        "sla": "99.9%",
        "latency": 41,
        "jitter": 5,
        "loss": "0.05%",
        "peak": 940,
        "avg": 610,
        "peak_time": "2025-07-02 21:40",
        "mtu": 1500,
        "asn": "64513",
        "nodes": 4,
        "milestone1": "2025-08-01",
        "milestone2": "2025-09-01",
        "advance": "40%",
        "warranty": "24 个月",
        "spare": 6,
        "review_date": "2025-06-28",
        "decision": "有条件通过",
        "risk": "低风险",
        "phase2": 48,
        "drill": 3,
        "rollback": 30,
        "concurrent": 3200,
    },
    {
        "key": "budget",
        "name": "西北节点新建工程",
        "city": "西安",
        "peer": "兰州",
        "bandwidth": 800,
        "prev_bandwidth": 200,
        "budget": 245,
        "model": "S6280-48Y6C",
        "core": "NE5000E-20",
        "modules": 12,
        "module_price": 0.65,
        "device_count": 18,
        "owner": "王磊",
        "date": "2026-03-01",
        "window": "每月首个周六 00:00-04:00",
        "sla": "99.99%",
        "latency": 67,
        "jitter": 8,
        "loss": "0.02%",
        "peak": 760,
        "avg": 430,
        "peak_time": "2025-12-11 19:05",
        "mtu": 9100,
        "asn": "64514",
        "nodes": 8,
        "milestone1": "2025-12-20",
        "milestone2": "2026-02-10",
        "advance": "25%",
        "warranty": "48 个月",
        "spare": 2,
        "review_date": "2025-11-08",
        "decision": "暂缓批复",
        "risk": "高风险",
        "phase2": 130,
        "drill": 1,
        "rollback": 60,
        "concurrent": 1500,
    },
]


def write_docx(domain: dict) -> None:
    path = CORPUS / f"{domain['key']}-plan.docx"
    document = Document()
    document.add_heading(f"{domain['name']}扩容方案", level=1)
    document.add_paragraph(
        f"本方案将{domain['city']}至{domain['peer']}的专线带宽由 {domain['prev_bandwidth']}Mbps "
        f"提升到 {domain['bandwidth']}Mbps，覆盖 {domain['concurrent']} 个并发会话。"
    )
    document.add_heading("投资与工期", level=2)
    document.add_paragraph(
        f"项目总投资 {domain['budget']} 万元，计划 {domain['date']} 前完成，项目负责人 {domain['owner']}，"
        f"服务等级目标 {domain['sla']}。"
    )
    document.add_heading("设备选型", level=2)
    table = document.add_table(rows=3, cols=3)
    table.style = "Table Grid"
    headers = ["设备", "型号", "数量"]
    for i, text in enumerate(headers):
        table.cell(0, i).text = text
    table.cell(1, 0).text = "汇聚交换机"
    table.cell(1, 1).text = domain["model"]
    table.cell(1, 2).text = str(domain["device_count"])
    table.cell(2, 0).text = "核心路由器"
    table.cell(2, 1).text = domain["core"]
    table.cell(2, 2).text = "2"
    document.add_heading("实施要求", level=2)
    document.add_paragraph(
        f"割接窗口为{domain['window']}，回退时限 {domain['rollback']} 分钟，割接前演练 {domain['drill']} 次。"
        f"光模块共 {domain['modules']} 个，需在 {domain['milestone1']} 前到货。"
    )
    document.save(path)


def write_xlsx(domain: dict) -> None:
    path = CORPUS / f"{domain['key']}-devices.xlsx"
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "设备报价"
    rows = [
        ["设备", "型号", "单价(万元)", "数量"],
        ["汇聚交换机", domain["model"], domain["module_price"] * 10, domain["device_count"]],
        ["光模块", "QSFP-40G", domain["module_price"], domain["modules"]],
        ["备件包", "SP-KIT", 1.2, domain["spare"]],
    ]
    for row in rows:
        sheet.append(row)
    sheet2 = workbook.create_sheet("交付里程碑")
    sheet2.append(["里程碑", "日期", "付款比例"])
    sheet2.append(["设备到货", domain["milestone1"], domain["advance"]])
    sheet2.append(["割接完成", domain["milestone2"], "40%"])
    sheet2.append(["终验", domain["date"], "30%"])
    sheet3 = workbook.create_sheet("合同条款")
    sheet3.append(["条款", "内容"])
    sheet3.append(["质保期", domain["warranty"]])
    sheet3.append(["服务等级", domain["sla"]])
    workbook.save(path)


def write_pptx(domain: dict) -> None:
    path = CORPUS / f"{domain['key']}-review.pptx"
    presentation = Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[1])
    slide.shapes.title.text = f"{domain['name']}扩容评审"
    slide.placeholders[1].text = (
        f"评审日期：{domain['review_date']}\n评审结论：{domain['decision']}\n风险等级：{domain['risk']}"
    )
    slide2 = presentation.slides.add_slide(presentation.slide_layouts[1])
    slide2.shapes.title.text = "关键指标"
    slide2.placeholders[1].text = (
        f"目标带宽：{domain['bandwidth']}Mbps\n投资：{domain['budget']} 万元\n"
        f"二期预算：{domain['phase2']} 万元\n割接回退时限：{domain['rollback']} 分钟"
    )
    slide3 = presentation.slides.add_slide(presentation.slide_layouts[1])
    slide3.shapes.title.text = "遗留问题"
    slide3.placeholders[1].text = (
        f"光模块 {domain['modules']} 个到货风险\n需安排 {domain['drill']} 次割接演练\n"
        f"负责人：{domain['owner']}"
    )
    presentation.save(path)


def write_md(domain: dict) -> None:
    path = CORPUS / f"{domain['key']}-latency.md"
    lines = [
        f"# {domain['name']}链路质量基线",
        "",
        f"- 采集城市：{domain['city']} ↔ {domain['peer']}",
        f"- 平均时延：{domain['latency']}ms",
        f"- 抖动上限：{domain['jitter']}ms",
        f"- 丢包率上限：{domain['loss']}",
        "",
        "| 指标 | 目标值 | 实测值 |",
        "| --- | --- | --- |",
        f"| 时延 | ≤{domain['latency'] + 5}ms | {domain['latency']}ms |",
        f"| 抖动 | ≤{domain['jitter'] + 2}ms | {domain['jitter']}ms |",
        "",
        "采样周期为 5 分钟，使用 iperf3 与 ping 双工具校验。",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def write_csv(domain: dict) -> None:
    path = CORPUS / f"{domain['key']}-traffic.csv"
    random.seed(domain["key"])
    rows = ["timestamp,link,mbps"]
    for hour in range(1, 13):
        value = max(50, domain["avg"] + random.randint(-80, 80))
        rows.append(f"2025-05-{hour:02d} 12:00,{domain['city']}-{domain['peer']},{value}")
    rows.append(f"{domain['peak_time']},{domain['city']}-{domain['peer']},{domain['peak']}")
    path.write_text("\n".join(rows), encoding="utf-8")


def write_json(domain: dict) -> None:
    path = CORPUS / f"{domain['key']}-nodes.json"
    payload = {
        "project": domain["name"],
        "nodeCount": domain["nodes"],
        "mtu": domain["mtu"],
        "bgpAsn": domain["asn"],
        "nodes": [
            {"name": f"{domain['city']}-POP-{i + 1}", "role": "edge" if i else "core"}
            for i in range(domain["nodes"])
        ],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def questions(domain: dict) -> list[dict]:
    key = domain["key"]
    items = [
        ("扩容后目标带宽是多少？", f"{key}-plan.docx", [f"{domain['bandwidth']}Mbps"]),
        ("项目总投资多少万元？", f"{key}-plan.docx", [f"{domain['budget']} 万元"]),
        ("汇聚交换机的型号是什么？", f"{key}-plan.docx", [domain["model"]]),
        ("核心路由器的型号是什么？", f"{key}-plan.docx", [domain["core"]]),
        ("光模块一共需要多少个？", f"{key}-plan.docx", [f"{domain['modules']} 个"]),
        ("计划哪一天前完成割接？", f"{key}-plan.docx", [domain["date"]]),
        ("项目负责人是谁？", f"{key}-plan.docx", [domain["owner"]]),
        ("服务等级目标是多少？", f"{key}-plan.docx", [domain["sla"]]),
        ("割接窗口是什么时间？", f"{key}-plan.docx", [domain["window"]]),
        ("割接回退时限是多少分钟？", f"{key}-plan.docx", [f"{domain['rollback']} 分钟"]),
        ("割接前需要演练几次？", f"{key}-plan.docx", [f"{domain['drill']} 次"]),
        ("覆盖多少并发会话？", f"{key}-plan.docx", [f"{domain['concurrent']} 个"]),
        ("扩容前的带宽是多少？", f"{key}-plan.docx", [f"{domain['prev_bandwidth']}Mbps"]),
        ("汇聚交换机需要多少台？", f"{key}-plan.docx", [f"{domain['device_count']}"]),
        ("光模块单价是多少万元？", f"{key}-devices.xlsx", [str(domain["module_price"])]),
        ("设备到货里程碑是哪一天？", f"{key}-devices.xlsx", [domain["milestone1"]]),
        ("割接完成里程碑是哪一天？", f"{key}-devices.xlsx", [domain["milestone2"]]),
        ("设备到货的付款比例是多少？", f"{key}-devices.xlsx", [domain["advance"]]),
        ("质保期多长？", f"{key}-devices.xlsx", [domain["warranty"]]),
        ("备件包数量是多少？", f"{key}-devices.xlsx", [f"{domain['spare']}"]),
        ("备件包单价是多少万元？", f"{key}-devices.xlsx", ["1.2 万元"]),
        ("终验付款比例是多少？", f"{key}-devices.xlsx", ["30%"]),
        ("评审结论是什么？", f"{key}-review.pptx", [domain["decision"]]),
        ("评审日期是哪一天？", f"{key}-review.pptx", [domain["review_date"]]),
        ("风险等级是什么？", f"{key}-review.pptx", [domain["risk"]]),
        ("二期预算是多少万元？", f"{key}-review.pptx", [f"{domain['phase2']} 万元"]),
        ("评审材料里提到几个遗留问题类别？", f"{key}-review.pptx", ["光模块", "演练"]),
        ("平均时延是多少毫秒？", f"{key}-latency.md", [f"{domain['latency']}ms"]),
        ("抖动上限是多少毫秒？", f"{key}-latency.md", [f"{domain['jitter']}ms"]),
        ("丢包率上限是多少？", f"{key}-latency.md", [domain["loss"]]),
        ("链路质量采样周期是多少？", f"{key}-latency.md", ["5 分钟"]),
        ("峰值带宽是多少 Mbps？", f"{key}-traffic.csv", [f"{domain['peak']}"]),
        ("峰值出现在什么时间？", f"{key}-traffic.csv", [domain["peak_time"]]),
        ("流量采样数据覆盖几个时间点？", f"{key}-traffic.csv", ["13"]),
        ("节点数量是多少？", f"{key}-nodes.json", [f"{domain['nodes']}"]),
        ("MTU 配置为多少？", f"{key}-nodes.json", [f"{domain['mtu']}"]),
        ("BGP AS 号是多少？", f"{key}-nodes.json", [domain["asn"]]),
        ("核心节点叫什么名字？", f"{key}-nodes.json", [f"{domain['city']}-POP-1"]),
        ("首个里程碑的付款比例？", f"{key}-devices.xlsx", [domain["advance"]]),
        ("合同里的服务等级条款是多少？", f"{key}-devices.xlsx", [domain["sla"]]),
    ]
    result = []
    for index, (question, file, keywords) in enumerate(items, start=1):
        result.append({
            "id": f"{key}-{index:03d}",
            "domain": key,
            "question": f"{domain['name']}：{question}",
            "file": file,
            "keywords": keywords,
        })
    return result


def main() -> None:
    CORPUS.mkdir(parents=True, exist_ok=True)
    DATASET.parent.mkdir(parents=True, exist_ok=True)
    all_cases = []
    for domain in DOMAINS:
        write_docx(domain)
        write_xlsx(domain)
        write_pptx(domain)
        write_md(domain)
        write_csv(domain)
        write_json(domain)
        all_cases.extend(questions(domain))
    with DATASET.open("w", encoding="utf-8", newline="\n") as handle:
        for case in all_cases:
            handle.write(json.dumps(case, ensure_ascii=False) + "\n")
    print(f"corpus={len(DOMAINS) * 6} files, dataset={len(all_cases)} cases -> {DATASET}")


if __name__ == "__main__":
    main()
