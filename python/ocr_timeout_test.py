"""
OCR 超时测试脚本
模拟长时间运行的 OCR 进程，用于测试 Java 端的超时保护机制
"""

import sys
import time
import json

def main():
    if len(sys.argv) != 3:
        print("Usage: python ocr_timeout_test.py <input_image> <output_json>", file=sys.stderr)
        sys.exit(1)

    output_path = sys.argv[2]

    print("OCR Timeout Test: Sleeping for 60 seconds...", file=sys.stderr)

    # 模拟长时间运行 - 应该被 Java 进程在 5-10 秒后杀死
    time.sleep(60)

    # 这段代码不应该被执行到
    result = {
        "success": True,
        "items": []
    }

    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print("OCR Timeout Test: Completed (this should not be printed)", file=sys.stderr)
    sys.exit(0)

if __name__ == "__main__":
    main()
