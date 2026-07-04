package com.skybook.praveen.notificationservice.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** Renders a QR code PNG for embedding as an inline email image. */
@Component
public class QrCodeGenerator {

    public byte[] generatePng(String content, int sizePixels) {

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePixels,
                    sizePixels,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();

        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Could not generate QR code", e);
        }
    }
}
