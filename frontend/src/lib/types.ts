export type Role = "ATTENDEE" | "ORGANIZER" | "STAFF";

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  roles: Role[];
  staffEventIds: string[];
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: UserProfile;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type EventStatus = "DRAFT" | "PUBLISHED" | "CANCELLED" | "COMPLETED";

export interface TicketType {
  id: string;
  eventId: string;
  name: string;
  description: string | null;
  priceCents: number;
  currency: string;
  capacity: number;
  held: number;
  sold: number;
  available: number;
  perOrderLimit: number;
  salesStart: string;
  salesEnd: string;
}

export interface EventSummary {
  id: string;
  organizerId: string;
  title: string;
  category: string;
  venueName: string;
  venueAddress: string;
  venueCapacity: number;
  startTime: string;
  endTime: string;
  timezone: string;
  status: EventStatus;
  waitingRoomEnabled: boolean;
  minPriceCents: number;
  totalAvailable: number;
}

export interface EventDetail {
  id: string;
  organizerId: string;
  title: string;
  description: string;
  category: string;
  venueName: string;
  venueAddress: string;
  venueCapacity: number;
  startTime: string;
  endTime: string;
  timezone: string;
  status: EventStatus;
  cancellationCutoffAt: string | null;
  waitingRoomEnabled: boolean;
  waitingRoomThreshold: number;
  version: number;
  createdAt: string;
  updatedAt: string;
  ticketTypes: TicketType[];
}

export interface StaffAssignment {
  id: string;
  eventId: string;
  userId: string;
  email: string;
  fullName: string;
  assignedAt: string;
}

export type OrderStatus =
  | "HELD"
  | "PENDING_PAYMENT"
  | "CONFIRMED"
  | "CANCELLED"
  | "EXPIRED"
  | "REFUNDED";

export interface OrderItem {
  id: string;
  ticketTypeId: string;
  ticketTypeName: string;
  quantity: number;
  unitPriceCents: number;
  lineTotalCents: number;
}

export interface LedgerEntry {
  id: string;
  provider: string;
  entryType: string;
  amountCents: number;
  currency: string;
  referenceCode: string | null;
  notes: string | null;
  actorUserId: string | null;
  createdAt: string;
}

export interface OrderResponse {
  id: string;
  userId: string;
  eventId: string;
  eventTitle: string;
  status: OrderStatus;
  totalCents: number;
  currency: string;
  holdExpiresAt: string | null;
  paymentDeadlineAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
  items: OrderItem[];
  ledger: LedgerEntry[];
}

export type TicketStatus = "VALID" | "CHECKED_IN" | "CANCELLED";

export interface TicketResponse {
  id: string;
  orderId: string;
  eventId: string;
  eventTitle: string;
  venueName: string;
  venueAddress: string;
  eventStartTime: string | null;
  eventEndTime: string | null;
  timezone: string;
  ticketTypeId: string;
  ticketTypeName: string;
  userId: string;
  attendeeName: string;
  ticketCode: string;
  qrToken: string;
  status: TicketStatus;
  checkedInAt: string | null;
  checkedInBy: string | null;
  createdAt: string;
}

export interface CheckInResponse {
  ticketId: string;
  eventId: string;
  ticketCode: string;
  ticketTypeName: string;
  attendeeName: string;
  status: string;
  checkedInAt: string;
  checkedInBy: string;
  checkedInByName: string;
}

export interface CheckInLog {
  id: string;
  ticketId: string;
  eventId: string;
  staffUserId: string;
  outcome: string;
  scannedAt: string;
  notes: string | null;
}

export interface WaitingRoomStatus {
  eventId: string;
  status: "ADMITTED" | "QUEUED";
  queueToken: string;
  position: number;
  estimatedWaitSeconds: number;
  pollAfterSeconds: number;
}

export type WaitlistStatus = "WAITING" | "OFFERED" | "CLAIMED" | "EXPIRED" | "CANCELLED";

export interface WaitlistEntry {
  id: string;
  eventId: string;
  eventTitle: string;
  ticketTypeId: string;
  ticketTypeName: string;
  userId: string;
  quantity: number;
  status: WaitlistStatus;
  position: number;
  offerOrderId: string | null;
  offeredAt: string | null;
  offerExpiresAt: string | null;
  createdAt: string;
}

export interface NotificationItem {
  id: string;
  eventId: string | null;
  type: string;
  title: string;
  message: string;
  referenceId: string | null;
  read: boolean;
  createdAt: string;
}

export interface NotificationPage {
  items: NotificationItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  unreadCount: number;
}

export interface TicketTypeMetric {
  ticketTypeId: string;
  name: string;
  capacity: number;
  soldCount: number;
  revenueCents: number;
  checkedInCount: number;
}

export interface CheckInBucketPoint {
  bucketStart: string;
  checkInCount: number;
}

export interface SalesBucketPoint {
  bucketStart: string;
  ticketsSold: number;
  revenueCents: number;
}

export interface DashboardResponse {
  eventId: string;
  eventTitle: string;
  status: EventStatus;
  venueCapacity: number;
  totalTicketCapacity: number;
  totalSold: number;
  totalRevenueCents: number;
  capacityUsedPercent: number;
  totalCheckedIn: number;
  checkInRatePercent: number;
  noShowCount: number;
  totalCancelled: number;
  ticketTypes: TicketTypeMetric[];
  topTicketTypes: TicketTypeMetric[];
  checkInCurve: CheckInBucketPoint[];
  salesOverTime: SalesBucketPoint[];
}

export interface AttendeeRecord {
  ticketId: string;
  ticketCode: string;
  ticketTypeId: string;
  ticketTypeName: string;
  userId: string;
  attendeeName: string;
  attendeeEmail: string;
  status: TicketStatus;
  checkedInAt: string | null;
  issuedAt: string;
}

