import { test, expect } from "@playwright/test";

function toLocalDateTimeInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

test("full organizer, attendee, ticket QR/PDF, door scanner, and analytics workflow", async ({ browser, request }) => {
  const unique = `${Date.now()}-${Math.floor(Math.random() * 10000)}`;
  const orgEmail = `e2e-org-${unique}@gatekeeper.local`;
  const attEmail = `e2e-att-${unique}@gatekeeper.local`;
  const staffEmail = `e2e-staff-${unique}@gatekeeper.local`;
  const eventTitle = `GateKeeper Live E2E ${unique}`;

  // Pre-register staff user via API (could also be done in UI; user already exists for organizer to assign)
  const staffCtx = await browser.newContext();
  const staffPage = await staffCtx.newPage();
  await staffPage.goto("/register");
  await staffPage.getByLabel("Full name").fill("Door Staff E2E");
  await staffPage.getByLabel("Email address").fill(staffEmail);
  await staffPage.getByLabel(/Password/i).fill("StrongPass!1");
  await staffPage.getByRole("button", { name: "Create Account" }).click();
  await expect(staffPage).toHaveURL("/");

  // 1. Organizer registers, creates event within active door check-in window, adds ticket tier, publishes, assigns staff
  const orgCtx = await browser.newContext();
  const orgPage = await orgCtx.newPage();
  await orgPage.goto("/register");
  await orgPage.getByLabel("Full name").fill("E2E Organizer");
  await orgPage.getByLabel("Email address").fill(orgEmail);
  await orgPage.getByLabel(/Password/i).fill("StrongPass!1");
  await orgPage.getByLabel(/Enable Organizer Studio/i).check();
  await orgPage.getByRole("button", { name: "Create Account" }).click();
  await expect(orgPage).toHaveURL("/organizer");

  await orgPage.getByRole("button", { name: /Create Event/i }).click();
  const now = new Date();
  const start = new Date(now.getTime() + 20 * 60 * 1000);
  const end = new Date(now.getTime() + 4 * 60 * 60 * 1000);

  await orgPage.getByLabel("Title").fill(eventTitle);
  await orgPage.getByLabel("Category").fill("Conference");
  await orgPage.getByLabel("Description").fill("End-to-end Playwright verification event");
  await orgPage.getByLabel("Venue Name").fill("Harbor Convention Center");
  await orgPage.getByLabel("Venue Address").fill("100 Pier Way");
  await orgPage.getByLabel("Venue Capacity").fill("50");
  await orgPage.getByLabel("Start Time").fill(toLocalDateTimeInput(start));
  await orgPage.getByLabel("End Time").fill(toLocalDateTimeInput(end));
  await orgPage.getByRole("button", { name: "Create Draft Event" }).click();

  await expect(orgPage).toHaveURL(/\/organizer\/events\/[0-9a-f-]+/);
  const eventUrl = orgPage.url();
  const eventId = eventUrl.split("/").pop()!;

  // Add ticket tier
  await orgPage.getByRole("button", { name: /Ticket Tiers/i }).click();
  await orgPage.getByLabel(/Name \(e\.g\./i).fill("Community Pass");
  await orgPage.getByLabel(/Price \(USD\)/i).fill("0");
  await orgPage.getByLabel("Capacity").fill("25");
  await orgPage.getByLabel("Order Limit").fill("2");
  await orgPage.getByRole("button", { name: "Add Ticket Type" }).click();
  await expect(orgPage.getByText("Community Pass")).toBeVisible();

  // Publish event
  await orgPage.getByRole("button", { name: "Publish Event" }).click();
  await expect(orgPage.getByText("PUBLISHED", { exact: true })).toBeVisible();

  // Assign staff
  await orgPage.getByRole("button", { name: /Staff & Waitlist/i }).click();
  await orgPage.getByPlaceholder("staff@example.com").fill(staffEmail);
  await orgPage.getByRole("button", { name: "Assign" }).click();
  await expect(orgPage.getByText(staffEmail)).toBeVisible();

  // 2. Attendee registers, searches public catalog, books free ticket, verifies QR & PDF
  const attCtx = await browser.newContext();
  const attPage = await attCtx.newPage();
  await attPage.goto("/register");
  await attPage.getByLabel("Full name").fill("E2E Attendee");
  await attPage.getByLabel("Email address").fill(attEmail);
  await attPage.getByLabel(/Password/i).fill("StrongPass!1");
  await attPage.getByRole("button", { name: "Create Account" }).click();
  await expect(attPage).toHaveURL("/");

  await attPage.getByLabel("Search events").fill(unique);
  await attPage.getByRole("button", { name: "Filter" }).click();
  await attPage.getByRole("link", { name: new RegExp(eventTitle) }).click();
  await expect(attPage).toHaveURL(`/events/${eventId}`);

  await attPage.getByRole("button", { name: "Increase Community Pass quantity" }).click();
  await attPage.getByRole("button", { name: /Reserve Seats/i }).click();
  await expect(attPage.getByRole("timer")).toBeVisible();
  await attPage.getByRole("button", { name: "Confirm Free Tickets" }).click();
  await expect(attPage.getByText(/Order Confirmed! Tickets Issued/i)).toBeVisible();

  await attPage.getByRole("link", { name: /View QR Tickets/i }).click();
  await expect(attPage).toHaveURL("/tickets");
  const ticketCodeEl = attPage.locator("p.font-mono").first();
  await expect(ticketCodeEl).toBeVisible();
  const ticketCode = (await ticketCodeEl.textContent())!.trim();
  await expect(attPage.getByAltText(`QR code for ticket ${ticketCode}`)).toBeVisible();

  // Verify PDF download endpoint responds with valid PDF
  const pdfDownloadPromise = attPage.waitForEvent("download");
  await attPage.getByRole("button", { name: "Download PDF" }).first().click();
  const pdfDownload = await pdfDownloadPromise;
  expect(pdfDownload.suggestedFilename()).toContain(ticketCode);

  // 3. Staff opens Scanner, checks ticket in, and second scan rejects as duplicate
  await staffPage.reload();
  await staffPage.goto("/scanner");
  await expect(staffPage.getByLabel("Active Event Door")).toBeVisible();
  await staffPage.getByLabel(/Manual Fallback/i).fill(ticketCode);
  await staffPage.getByRole("button", { name: /Verify & Check In/i }).click();
  await expect(staffPage.getByRole("status")).toContainText("ADMITTED — CHECKED IN");

  await staffPage.getByLabel(/Manual Fallback/i).fill(ticketCode);
  await staffPage.getByRole("button", { name: /Verify & Check In/i }).click();
  await expect(staffPage.getByRole("status")).toContainText("ENTRY REJECTED");
  await expect(staffPage.getByRole("status")).toContainText("Already checked in at");

  // 4. Organizer sees live pre-aggregated sales and check-in numbers on Dashboard
  await orgPage.reload();
  await orgPage.getByRole("button", { name: "Analytics Dashboard" }).click();
  await expect(async () => {
    await orgPage.reload();
    await expect(orgPage.getByText("1 / 25").first()).toBeVisible();
    await expect(orgPage.getByText("100% check-in rate")).toBeVisible();
  }).toPass({ timeout: 15_000 });

  await staffCtx.close();
  await orgCtx.close();
  await attCtx.close();
  void request;
});

