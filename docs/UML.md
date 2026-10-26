# UML — NightVenue

Pendamping: `PRD.md`, `DATABASE.md`, `APPLICATION_ARCHITECTURE.md`, `DECISIONS.md`. Semua diagram Mermaid. Label memakai `<br/>`, bukan `\n`.

---

## 1. Use Case

```mermaid
flowchart LR
    CUST(["Customer"])
    CASH(["Cashier"])
    MGR(["Manager"])
    ADM(["Admin"])
    MID(["Midtrans"])

    subgraph Sistem["NightVenue"]
        U1["Chat venue"]
        U2["Lihat botol titipan"]
        U3["Buka dan tutup order"]
        U4["Tambah item"]
        U5["Bayar CASH / EDC / QRIS"]
        U6["VOID order"]
        U7["Titip dan redeem botol"]
        U8["Buka dan submit shift"]
        U9["Review shift"]
        U10["Approve VOID di atas ambang"]
        U11["Kelola katalog dan resep"]
        U12["Adjust stok"]
        U13["Prediksi stok"]
        U14["Kelola user dan venue"]
        U15["Kirim webhook pembayaran"]
    end

    CUST --> U1
    CUST --> U2
    CASH --> U1
    CASH --> U3
    CASH --> U4
    CASH --> U5
    CASH --> U6
    CASH --> U7
    CASH --> U8
    MGR --> U9
    MGR --> U10
    MGR --> U12
    MGR --> U13
    ADM --> U11
    ADM --> U14
    ADM --> U12
    ADM --> U13
    MID --> U15
```

Role tidak hierarkis. Otorisasi mengikuti matriks kapabilitas eksplisit di `PRD.md` bagian 4, tanpa pewarisan hak.

---

## 2. Diagram Kelas Domain

```mermaid
classDiagram
    class Venue {
        +Long id
        +String timezone
        +int businessDayCutoffHour
        +long voidManagerApprovalThreshold
    }
    class User {
        +Long id
        +String email
        +Role role
    }
    class Customer {
        +Long id
        +boolean ageVerified
    }
    class Product {
        +Long id
        +long price
        +boolean bottleKeepable
    }
    class Ingredient {
        +Long id
        +Unit unit
    }
    class Recipe
    class RecipeItem {
        +BigDecimal quantity
    }
    class StockLocation {
        +LocationType type
    }
    class InventoryItem {
        +BigDecimal quantity
    }
    class InventoryTransaction {
        +TxType type
        +BigDecimal delta
        +BigDecimal balanceAfter
        +LocalDate businessDate
    }
    class VenueTable {
        +String area
        +long minimumSpend
        +TableStatus status
    }
    class Order {
        +LocalDate businessDate
        +OrderStatus status
        +long totalDue
        +long paidTotal
    }
    class OrderItem {
        +ItemType itemType
        +ItemStatus status
        +long lineTotal
    }
    class Payment {
        +LocalDate businessDate
        +Method method
        +PaymentStatus status
        +long amount
    }
    class PaymentEvent {
        +String eventKey
        +EventResult result
    }
    class VoidLog {
        +Disposition disposition
        +String reason
    }
    class CashierShift {
        +LocalDate businessDate
        +ShiftStatus status
        +long openingFloat
        +long variance
    }
    class BottleVault {
        +int originalVolumeMl
        +int remainingVolumeMl
        +VaultStatus status
        +String qrTokenHash
    }
    class BottleVaultTransaction {
        +VaultTxType type
        +int volumeDeltaMl
        +boolean ownerConfirmed
    }
    class StockForecast

    Venue "1" --> "*" VenueTable
    Venue "1" --> "*" StockLocation
    Venue "1" --> "*" Product
    Venue "1" --> "*" Order
    User "*" --> "*" Venue : user_venues
    User "1" --> "0..1" Customer : akun opsional
    Product "1" --> "0..1" Recipe
    Recipe "1" --> "1..*" RecipeItem
    RecipeItem "*" --> "1" Ingredient
    StockLocation "1" --> "*" InventoryItem
    InventoryItem "*" --> "1" Ingredient
    InventoryItem "1" --> "*" InventoryTransaction
    VenueTable "0..1" --> "*" Order
    Customer "0..1" --> "*" Order
    User "1" --> "*" Order : kasir
    Order "1" --> "1..*" OrderItem
    OrderItem "*" --> "1" Product
    Order "1" --> "*" Payment
    Payment "0..1" --> "*" PaymentEvent
    CashierShift "0..1" --> "*" Payment : kas shift
    User "1" --> "*" CashierShift
    OrderItem "1" --> "0..1" VoidLog
    Customer "1" --> "*" BottleVault
    BottleVault "1" --> "*" BottleVaultTransaction
    BottleVault "0..1" --> "0..1" OrderItem : sumber pembelian
    BottleVaultTransaction "*" --> "0..1" OrderItem : redeem
    Ingredient "1" --> "*" StockForecast
```

---

## 3. Diagram Kelas Service

```mermaid
classDiagram
    class BusinessDateService {
        +businessDate(Instant, ZoneId, int cutoffHour) LocalDate
        +currentBusinessDate(ZoneId, int cutoffHour) LocalDate
    }
    class OrderService {
        +open(cmd) Order
        +addItems(orderId, items, key) Order
        +serve(orderId, itemId)
    }
    class InventoryService {
        +deductForItem(item, barLocation)
        +adjust(cmd)
    }
    class VoidService {
        +voidOrder(orderId, reason, disposition, key)
    }
    class PaymentService {
        +create(orderId, cmd, key) Payment
        +handleWebhook(payload, signature)
    }
    class PaymentGateway {
        <<interface>>
        +createTransaction(payment)
        +fetchStatus(providerOrderId)
    }
    class MidtransGateway
    class ShiftService {
        +open(cashier, openingFloat) CashierShift
        +submit(shiftId, actualCash)
        +review(shiftId, note)
        +forceReviewAtCutoff()
    }
    class BottleService {
        +deposit(orderItemId, customerId) DepositResult
        +redeem(qrToken, orderId, volumeMl, ownerConfirmed, key)
        +expireJob()
    }
    class ForecastService {
        +forecast(ingredientId) StockForecast
    }
    class ChatRouter {
        +handle(message, aiKey, provider) ChatResponse
    }
    class MlClient {
        +classify(message) ClassifyResult
        +forecast(series) ForecastResult
    }
    class LlmProvider {
        <<interface>>
        +complete(context, message, key) LlmResult
    }
    class GeminiProvider
    class GroqProvider

    OrderService --> BusinessDateService
    OrderService --> InventoryService
    VoidService --> BusinessDateService
    VoidService --> InventoryService
    PaymentService --> BusinessDateService
    PaymentService --> PaymentGateway
    PaymentService --> ShiftService : validasi shift OPEN
    MidtransGateway ..|> PaymentGateway
    ShiftService --> BusinessDateService
    BottleService --> BusinessDateService
    ForecastService --> MlClient
    ChatRouter --> MlClient
    ChatRouter --> LlmProvider
    GeminiProvider ..|> LlmProvider
    GroqProvider ..|> LlmProvider
```

---

## 4. Sequence

### 4.1 Buat order dan tambah item (Stock-at-Order)

```mermaid
sequenceDiagram
    actor K as Kasir
    participant A as OrderService
    participant B as BusinessDateService
    participant I as InventoryService
    participant D as PostgreSQL

    K->>A: POST /api/v1/orders (meja)
    A->>B: currentBusinessDate(venue.timezone, venue.businessDayCutoffHour)
    B-->>A: business_date
    A->>D: INSERT order OPEN (unik per meja aktif)
    A-->>K: order

    K->>A: POST /api/v1/orders/id/items (Idempotency-Key)
    A->>D: BEGIN
    A->>D: kunci baris order (FOR UPDATE)
    A->>D: INSERT order_items (dapat order_item_id)
    A->>I: deduct untuk tiap bahan (urut ID asc)
    I->>D: UPDATE inventory_items WHERE quantity >= need
    alt rows = 0
        D-->>I: 0 baris
        A->>D: ROLLBACK (order_item ikut batal)
        A-->>K: 409 INSUFFICIENT_STOCK
    else rows = 1
        I->>D: INSERT ledger SALE_DEDUCT (ref order_item_id)
        A->>D: hitung ulang total
        A->>D: COMMIT
        A-->>K: 200 order
    end
```

### 4.2 Pembayaran tunai dengan pengecekan shift

```mermaid
sequenceDiagram
    actor K as Kasir
    participant P as PaymentService
    participant S as ShiftService
    participant D as PostgreSQL

    K->>P: POST /api/v1/orders/id/payments (CASH, Idempotency-Key)
    P->>S: shift OPEN milik pemroses (semua role) dan business date sama dengan sekarang?
    alt tidak
        P-->>K: 409 SHIFT_NOT_OPEN
    else ya
        P->>D: BEGIN
        P->>D: kunci baris order (FOR UPDATE)
        P->>D: sisa = total_due - PAID - PENDING, tolak 422 bila amount melebihi
        P->>D: INSERT payment PAID (shift_id, business_date)
        P->>D: hitung paid_total
        alt minimum spend belum terpenuhi
            P->>D: ROLLBACK
            P-->>K: 422 MINIMUM_SPEND_NOT_MET
        else sebagian
            P->>D: order PARTIALLY_PAID
        else lunas
            P->>D: order PAID, closed_at
        end
        P->>D: COMMIT
        P-->>K: payment
    end
```

### 4.3 QRIS dan webhook duplikat

```mermaid
sequenceDiagram
    participant M as Midtrans
    participant A as PaymentService
    participant D as PostgreSQL

    M->>A: webhook 1
    A->>A: verifikasi signature
    A->>M: Status API
    A->>D: INSERT payment_events ON CONFLICT DO NOTHING (1 baris)
    A->>D: kunci baris order, payment PAID, order dihitung ulang
    A-->>M: 200
    M->>A: webhook 1 (kirim ulang)
    A->>A: verifikasi signature
    A->>D: INSERT payment_events ON CONFLICT DO NOTHING (0 baris)
    D-->>A: event_key sudah ada
    A->>A: no-op, dicatat di log terstruktur
    A-->>M: 200
```

### 4.4 VOID order

```mermaid
sequenceDiagram
    actor K as Kasir/Manager
    participant V as VoidService
    participant D as PostgreSQL

    K->>V: POST /api/v1/orders/id/void (alasan, disposition, Idempotency-Key)
    V->>V: cek ambang dan peran (WASTED hanya Manager/Admin)
    V->>D: ada payment PENDING atau PAID?
    alt ada
        V-->>K: 409 ORDER_HAS_PAYMENTS
    else tidak ada
        V->>D: BEGIN
        V->>D: UPDATE orders SET VOIDED WHERE status = OPEN
        alt rows = 0
            V->>D: ROLLBACK
            V-->>K: 409 sudah void
        else rows = 1
            loop tiap item aktif
                V->>D: INSERT void_logs (UQ order_item_id)
                V->>D: item VOIDED
                opt RESTORED
                    V->>D: balik baris ledger SALE_DEDUCT, VOID_RETURN
                end
            end
            V->>D: audit ORDER_VOID
            V->>D: COMMIT
            V-->>K: 200
        end
    end
```

### 4.5 Deposit botol

```mermaid
sequenceDiagram
    actor K as Kasir
    participant B as BottleService
    participant D as PostgreSQL

    K->>B: POST /api/v1/bottles (orderItemId, customerId, Idempotency-Key)
    B->>D: order PAID, produk keepable, quantity 1, customer age_verified, item belum punya vault?
    alt syarat tidak terpenuhi
        B-->>K: 422
    else ok
        B->>B: buat token acak 128 bit, hitung SHA-256
        B->>D: INSERT bottle_vaults (qr_token_hash), transaksi DEPOSIT
        B-->>K: label_code + token mentah (sekali)
    end
```

### 4.6 Redeem botol

```mermaid
sequenceDiagram
    actor K as Kasir
    participant B as BottleService
    participant D as PostgreSQL

    K->>B: POST /api/v1/bottles/redeem (qrToken, orderId, volumeMl, ownerConfirmed, Idempotency-Key)
    B->>D: cari vault dari hash token
    B->>B: order.customer_id sama dengan vault.customer_id dan ownerConfirmed true?
    alt tidak
        B-->>K: 403 / 422
    else ya
        B->>D: BEGIN
        B->>D: tambah order_item BOTTLE_REDEMPTION
        B->>D: UPDATE bottle_vaults conditional (ACTIVE, belum expired, sisa cukup)
        alt rows = 0
            B->>D: ROLLBACK
            B-->>K: 409
        else rows = 1
            B->>D: INSERT bottle_vault_transactions REDEEM
            B->>D: COMMIT
            B-->>K: sisa volume
        end
    end
```

### 4.7 Shift: buka, submit, review, cutoff

```mermaid
sequenceDiagram
    actor K as Kasir
    actor M as Manager
    participant S as ShiftService
    participant D as PostgreSQL
    participant J as Cutoff Job

    K->>S: buka shift (opening float)
    S->>D: INSERT OPEN (UQ satu OPEN per kasir)
    K->>S: submit kas aktual
    S->>D: expected = float + SUM CASH PAID per shift_id
    S->>D: SUBMITTED, variance
    M->>S: review (catatan, bukan pemilik shift)
    S->>D: RECONCILED

    Note over J,D: Jalur cutoff
    J->>D: shift OPEN melewati cutoff?
    J->>D: MANAGER_REVIEW, forced_review_at, audit
    M->>S: review
    S->>D: RECONCILED
```

### 4.8 Chat lokal, fallback, eskalasi LLM

```mermaid
sequenceDiagram
    actor U as Pengguna
    participant C as ChatRouter
    participant ML as FastAPI
    participant D as PostgreSQL
    participant L as LlmProvider

    U->>C: POST /api/v1/chat (pesan, X-AI-Key opsional)
    C->>C: rate limit dan validasi
    C->>ML: classify (timeout 2 dtk)
    alt ML gagal
        C->>C: fallback regex maksimal 5 kategori
    else sukses
        ML-->>C: intent, confidence
    end
    alt intent lokal dan confidence di atas theta
        C->>D: ambil data
        C-->>U: jawaban template (0 token)
    else OUT_OF_SCOPE
        C-->>U: penolakan (0 token)
    else perlu LLM
        alt tidak ada key
            C-->>U: ADVANCED_AI_REQUIRED
        else ada key
            C->>D: konteks publik venue
            C->>L: complete
            L-->>C: jawaban dan jumlah token
            C-->>U: jawaban
        end
    end
    C->>D: INSERT chat_route_logs (tanpa isi pesan dan tanpa key)
```

### 4.9 Forecasting

```mermaid
sequenceDiagram
    actor M as Manager
    participant F as ForecastService
    participant D as PostgreSQL
    participant ML as FastAPI

    M->>F: prediksi stok bahan X
    F->>D: konsumsi per business_date dari ledger
    alt kurang dari 28 hari
        F-->>M: 422 data tidak cukup
    else cukup
        F->>ML: forecast (timeout 10 dtk)
        alt ML gagal
            F-->>M: 503 (hanya fitur ini)
        else sukses
            ML-->>F: prediksi, model MAE, baseline MAE
            F->>D: INSERT stock_forecasts
            F-->>M: hasil dan perbandingan baseline
        end
    end
```

---

## 5. State Machine

### 5.1 Order

```mermaid
stateDiagram-v2
    [*] --> OPEN
    OPEN --> PARTIALLY_PAID : payment PAID sebagian
    OPEN --> PAID : lunas dan minimum spend terpenuhi
    PARTIALLY_PAID --> PAID : sisa lunas
    OPEN --> VOIDED : VOID (tanpa payment PENDING atau PAID)
    PAID --> REFUNDED : dicadangkan, deferred
    VOIDED --> [*]
    PAID --> [*]
```

### 5.2 Order item

```mermaid
stateDiagram-v2
    [*] --> ORDERED
    ORDERED --> SERVED : disajikan
    ORDERED --> VOIDED : VOID
    SERVED --> VOIDED : VOID (selalu WASTED)
```

### 5.3 Payment

```mermaid
stateDiagram-v2
    [*] --> PENDING : QRIS dibuat
    [*] --> PAID : CASH / EDC
    PENDING --> PAID : settlement
    PENDING --> FAILED
    PENDING --> EXPIRED
    PENDING --> CANCELLED
    PAID --> REFUNDED : dicadangkan, deferred
```

Transisi mundur dari status terminal ditolak (`IGNORED_STALE`).

### 5.4 Bottle vault

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : DEPOSIT
    ACTIVE --> ACTIVE : REDEEM sebagian
    ACTIVE --> EMPTY : REDEEM habis
    ACTIVE --> EXPIRED : job expiry
    ACTIVE --> RELEASED : RELEASE
```

### 5.5 Shift

```mermaid
stateDiagram-v2
    [*] --> OPEN
    OPEN --> SUBMITTED : kasir submit
    OPEN --> MANAGER_REVIEW : cutoff terlewati
    SUBMITTED --> RECONCILED : manager review
    MANAGER_REVIEW --> RECONCILED : manager review
```

---

## 6. Activity: Checkout

```mermaid
flowchart TD
    A["Buka order di meja"] --> B["Tambah item"]
    B --> C{"Stok cukup?"}
    C -- tidak --> C1["409 dan rollback"]
    C -- ya --> D["Potong stok plus ledger"]
    D --> E{"Tambah item lagi?"}
    E -- ya --> B
    E -- tidak --> F["Pilih pembayaran"]
    F --> G{"Metode"}
    G -- CASH --> H{"Shift OPEN?"}
    H -- tidak --> H1["409"]
    H -- ya --> I["Payment PAID"]
    G -- CARD_EDC --> I
    G -- QRIS --> J["Payment PENDING lalu webhook"]
    J --> I
    I --> K{"Lunas dan minimum spend terpenuhi?"}
    K -- tidak --> F
    K -- ya --> L["Order PAID"]
    L --> M{"Item botol keepable dan customer terverifikasi?"}
    M -- ya --> N["Opsional: deposit botol"]
    M -- tidak --> O["Selesai"]
    N --> O
```
