# Changelog

## 2.0.1
- Sửa chiến đấu Aggressive/Protect: kiểm tra "chạm tới" theo hộp va chạm, tự lao tới mục tiêu khi bị kẹt, không còn lỗi khi pet khác thế giới
- Một pet gặp lỗi không còn làm dừng cả vòng xử lý các pet khác
- Mới: /omnipets debug - hiện pet đang làm gì (tìm mục tiêu, chạy tới, sát thương, bị plugin khác hủy)

## 2.0.0
Bản tổng hợp toàn bộ hệ thống OmniPets.
- 5 hành vi: Aggressive, Farm, Protect, Friendly, Aura Farming
- Tiến hóa theo cấp, cưỡi và bay, ba lô pet, độ đói, thuần hóa, giao dịch pet (/pettrade, Vault tùy chọn)
- 123 cây kỹ năng dựng sẵn + effect tùy chỉnh (effects.yml), 7 loại effect
- Animation hạt + âm thanh (animations.yml), 13 sự kiện, gắn riêng được vào từng effect
- Web editor: Skill trees, Effects, Animations (xem trước), file thô; có kiểm tra dữ liệu phía server
- Hook mô hình 3D: ModelEngine, BetterModel (best-effort), MythicMobs, ItemsAdder
- SQLite hoặc MySQL, Citizens (NPC mở /petstore), PlaceholderAPI, Rồng Ender (thử nghiệm)
- Đa ngôn ngữ: vi, en (thêm file lang/<mã>.yml để có thêm), /petlang
- Mới: /omnipets demo - phát lần lượt mọi animation để quay video

## 1.3.0 Animation | 1.2.0 Web editor skill tree/effect | 1.1.0 Hook 3D, MySQL, trade, PAPI, Rồng Ender | 1.0.0 Bản đầu
