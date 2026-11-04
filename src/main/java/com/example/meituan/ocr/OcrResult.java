package com.example.meituan.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR 识别结果
 */
public class OcrResult {
    private final boolean success;
    private final List<OcrTextBox> textBoxes;
    private final String errorMessage;

    private OcrResult(boolean success, List<OcrTextBox> textBoxes, String errorMessage) {
        this.success = success;
        this.textBoxes = textBoxes != null ? new ArrayList<>(textBoxes) : new ArrayList<>();
        this.errorMessage = errorMessage;
    }

    public static OcrResult success(List<OcrTextBox> textBoxes) {
        return new OcrResult(true, textBoxes, null);
    }

    public static OcrResult failure(String errorMessage) {
        return new OcrResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public List<OcrTextBox> getTextBoxes() {
        return Collections.unmodifiableList(textBoxes);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getTextBoxCount() {
        return textBoxes.size();
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("OcrResult[success=true, textBoxCount=%d]", textBoxes.size());
        } else {
            return String.format("OcrResult[success=false, error='%s']", errorMessage);
        }
    }
}
