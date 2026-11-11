package com.gatekeeper.ticket;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class TicketPdfService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM dd yyyy 'at' HH:mm z");

    private final QrCodeImageService qrCodeImageService;

    public TicketPdfService(QrCodeImageService qrCodeImageService) {
        this.qrCodeImageService = qrCodeImageService;
    }

    public byte[] renderTicketPdf(
            String eventTitle,
            String venueName,
            String venueAddress,
            Instant startTime,
            String timezone,
            String ticketTypeName,
            String attendeeName,
            String ticketCode,
            String qrToken,
            TicketStatus status
    ) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            Font brandFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(37, 99, 235));
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, new Color(15, 23, 42));
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(100, 116, 139));
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 12, new Color(15, 23, 42));
            Font codeFont = FontFactory.getFont(FontFactory.COURIER_BOLD, 14, new Color(15, 23, 42));

            Paragraph brand = new Paragraph("GATEKEEPER E-TICKET", brandFont);
            brand.setSpacingAfter(8f);
            document.add(brand);

            Paragraph title = new Paragraph(eventTitle, titleFont);
            title.setSpacingAfter(16f);
            document.add(title);

            PdfPTable card = new PdfPTable(new float[]{2.2f, 1.3f});
            card.setWidthPercentage(100);

            PdfPCell detailsCell = new PdfPCell();
            detailsCell.setPadding(16f);
            detailsCell.setBorderColor(new Color(203, 213, 225));
            detailsCell.setBorderWidth(1f);

            ZoneId zone = ZoneId.of(timezone);
            String formattedDate = DATE_FORMATTER.format(startTime.atZone(zone));

            addField(detailsCell, "ATTENDEE", attendeeName, labelFont, valueFont);
            addField(detailsCell, "TICKET TYPE", ticketTypeName, labelFont, valueFont);
            addField(detailsCell, "DATE & TIME", formattedDate, labelFont, valueFont);
            addField(detailsCell, "VENUE", venueName + "\n" + venueAddress, labelFont, valueFont);
            addField(detailsCell, "TICKET CODE", ticketCode, labelFont, codeFont);
            addField(detailsCell, "STATUS", status.name(), labelFont, valueFont);

            byte[] qrPng = qrCodeImageService.generatePng(qrToken, 240);
            Image qrImage = Image.getInstance(qrPng);
            qrImage.scaleToFit(170, 170);
            qrImage.setAlignment(Element.ALIGN_CENTER);

            PdfPCell qrCell = new PdfPCell();
            qrCell.setPadding(16f);
            qrCell.setBorderColor(new Color(203, 213, 225));
            qrCell.setBorderWidth(1f);
            qrCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            qrCell.addElement(qrImage);

            Paragraph scanCaption = new Paragraph("Present this QR at entry", labelFont);
            scanCaption.setAlignment(Element.ALIGN_CENTER);
            scanCaption.setSpacingBefore(8f);
            qrCell.addElement(scanCaption);

            card.addCell(detailsCell);
            card.addCell(qrCell);
            document.add(card);

            Paragraph footer = new Paragraph(
                    "Each ticket admits one guest and can be scanned once. Do not share the QR code publicly.",
                    FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(100, 116, 139))
            );
            footer.setSpacingBefore(14f);
            document.add(footer);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate ticket PDF", e);
        }
    }

    private void addField(PdfPCell cell, String label, String value, Font labelFont, Font valueFont) {
        Paragraph lbl = new Paragraph(label, labelFont);
        lbl.setSpacingBefore(6f);
        cell.addElement(lbl);
        Paragraph val = new Paragraph(new Phrase(value, valueFont));
        val.setSpacingAfter(4f);
        cell.addElement(val);
    }
}
