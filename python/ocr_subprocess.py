"""
OCR 子进程 - 使用 RapidOCR
输入：图像文件路径
输出：JSON 文件

JSON 格式：
{
  "success": true,
  "items": [
    {
      "text": "识别的文字",
      "confidence": 0.98,
      "box": [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
    }
  ]
}
"""

import sys
import json
from pathlib import Path

def main():
    if len(sys.argv) != 3:
        print("Usage: python ocr_subprocess.py <input_image> <output_json>", file=sys.stderr)
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    try:
        # 检查输入文件
        if not Path(input_path).exists():
            raise FileNotFoundError(f"Input file not found: {input_path}")

        # 导入 RapidOCR
        try:
            from rapidocr_onnxruntime import RapidOCR
        except ImportError:
            # 如果没有安装 RapidOCR，返回错误
            result = {
                "success": False,
                "error": "RapidOCR not installed. Please run: pip install rapidocr-onnxruntime"
            }
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            sys.exit(1)

        # 初始化 OCR
        ocr = RapidOCR()

        # 执行 OCR
        result_data, elapse = ocr(input_path)

        if result_data is None or len(result_data) == 0:
            # 没有识别到文字
            result = {
                "success": True,
                "items": []
            }
        else:
            # 转换结果格式
            items = []
            for item in result_data:
                box, text, confidence = item
                items.append({
                    "text": text,
                    "confidence": float(confidence),
                    "box": [[int(x), int(y)] for x, y in box]
                })

            result = {
                "success": True,
                "items": items
            }

        # 写入输出文件
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

        sys.exit(0)

    except Exception as e:
        # 错误处理
        result = {
            "success": False,
            "error": str(e)
        }
        try:
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
        except:
            pass

        print(f"OCR Error: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
