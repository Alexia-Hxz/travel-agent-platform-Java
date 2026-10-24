"""生成两张测试资料：一张带结构化字段的 PDF、一张带文字的 PNG。

只用标准库 + Pillow（工作区已带）。目的是让 api_smoke.py 能端到端验证
「上传 → 解析 → 7 类字段」，而不必依赖用户自己的文件。
"""

from __future__ import annotations

import sys
from pathlib import Path

OUTPUT = Path(__file__).resolve().parent.parent / "tmp"

PDF_LINES = [
    "Hotel Name: Nanjing Jinling Hotel",
    "Address: 2 Hanzhong Road, Nanjing",
    "Order No: HTL20261101001",
    "Airport: Nanjing Lukou International Airport",
    "Attraction Name: Nanjing Museum",
    "Check-in 2026-11-01 14:00",
    "Check-out 2026-11-04 12:00",
]


def escape_pdf_text(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def build_pdf(lines: list[str]) -> bytes:
    """手搓一个最小的单页 PDF：Helvetica 12pt，每行 22pt 行距。"""
    content_parts = ["BT", "/F1 12 Tf", "50 760 Td"]
    for index, line in enumerate(lines):
        if index:
            content_parts.append("0 -22 Td")
        content_parts.append(f"({escape_pdf_text(line)}) Tj")
    content_parts.append("ET")
    content = "\n".join(content_parts).encode("latin-1")

    objects: list[bytes] = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream",
    ]

    output = bytearray(b"%PDF-1.4\n")
    offsets: list[int] = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(output))
        output += f"{number} 0 obj\n".encode()
        output += body
        output += b"\nendobj\n"

    xref_offset = len(output)
    output += f"xref\n0 {len(objects) + 1}\n".encode()
    output += b"0000000000 65535 f \n"
    for offset in offsets:
        output += f"{offset:010d} 00000 n \n".encode()
    output += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n"
    ).encode()
    return bytes(output)


def build_png(path: Path) -> None:
    from PIL import Image, ImageDraw, ImageFont

    image = Image.new("RGB", (1000, 620), "white")
    draw = ImageDraw.Draw(image)
    font = None
    for candidate in (
        r"C:\Windows\Fonts\arialbd.ttf",
        r"C:\Windows\Fonts\arial.ttf",
        r"C:\Windows\Fonts\segoeui.ttf",
    ):
        if Path(candidate).exists():
            font = ImageFont.truetype(candidate, 30)
            break
    if font is None:
        font = ImageFont.load_default()

    lines = [
        "HOTEL CONFIRMATION",
        "Hotel Name: Jinling Hotel Nanjing",
        "Address: 2 Hanzhong Road, Qinhuai District",
        "Order No: HTL-2026-1101-7788",
        "Check-in: 2026-11-01 14:00",
        "Check-out: 2026-11-04 12:00",
        "Airport: Nanjing Lukou International Airport",
        "Attraction Name: Nanjing Museum",
    ]
    y = 40
    for line in lines:
        draw.text((40, y), line, fill="black", font=font)
        y += 62
    image.save(path)


def main() -> int:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    pdf_path = OUTPUT / "hotel_booking.pdf"
    png_path = OUTPUT / "hotel_booking.png"
    pdf_path.write_bytes(build_pdf(PDF_LINES))
    build_png(png_path)
    print(f"wrote {pdf_path} ({pdf_path.stat().st_size} bytes)")
    print(f"wrote {png_path} ({png_path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
