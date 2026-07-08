package com.skybook.praveen.notificationservice.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/** Converts {@link TicketPdfTemplate}'s XHTML into PDF bytes via openhtmltopdf. */
@Component
public class TicketPdfRenderer {

    public byte[] render(String xhtml) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not render ticket PDF", e);
        }
    }
}
