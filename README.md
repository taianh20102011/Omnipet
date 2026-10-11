# OmniPets 2.0.1 (Paper 1.21+, Java 21)

Build:  mvn clean package   ->  target/OmniPets-2.0.1.jar  ->  bỏ vào plugins/
Lệnh:   /petinfo /petinventory /petbehavior /petswitch /petstore /petname /petskills /petride /petfly
Admin:  /omnipets reload | types | give <người> <loại> [tên] | setlevel <người> <cấp> | editor
Hành vi: aggressive, farm, protect, friendly, aura-farming
Chơi:   chuột phải pet = cưỡi, shift+chuột phải = menu hành vi, cầm đồ ăn + chuột phải = cho ăn,
        cầm tame-item + chuột phải mob hoang = thuần hóa (xem config.yml -> pets)

## v1.1
- Mô hình 3D: pets.<loại>.model.provider = modelengine | bettermodel | mythicmobs | itemsadder
- 123 cây kỹ năng (12 hệ x 10 vai trò + 3 mẫu); pet lọc cây bằng mẫu: "*_tanker", "fire_*", "*"
- MySQL: database.type: mysql (+ join-delay-ticks khi dùng nhiều server)
- Rồng Ender: dragon.enabled: true (thử nghiệm); Citizens: citizens.npc-name; PlaceholderAPI: %omnipets_*%
- Giao dịch: /pettrade <người> [giá] | accept | deny (giá > 0 cần Vault)
- Ngôn ngữ: /petlang <mã>; có sẵn vi, en; thêm file lang/<mã>.yml để thêm ngôn ngữ

## v1.2 - Web editor cho cây kỹ năng & effect
- /omnipets editor -> tab "Skill trees": tạo/sửa/xóa cây, node, yêu cầu, inherit, effect (xem trước dạng cột)
- Tab "Effects": tạo effect riêng (effects.yml): OWNER_ATTRIBUTE, PET_ATTRIBUTE, OWNER_POTION, HIT_POTION, AURA_DAMAGE, HEAL_PET_ON_HIT, XP_MULT
- Mọi thứ nằm trong thư mục plugin của từng server -> mỗi server một bộ riêng
- Cây bị xóa được đổi thành skilltrees/<id>.yml.deleted (khôi phục bằng cách đổi lại tên)

## v1.3 - Animation
- animations.yml: hiệu ứng hạt + âm thanh (RING, SPIRAL, HELIX, BURST, SPHERE, PILLAR, BEAM); chỉnh + xem trước trong web editor (tab Animations)
- Gắn vào 13 sự kiện (summon, store, level-up, evolve, tame, unlock, hit, fire-hit, slow-hit, heal-owner, aura-pulse, harvest, mount)
  hoặc vào từng effect tùy chỉnh (tham số "animation"); mỗi loại pet ghi đè được: pets.<loại>.animations.<sự kiện>
- Thử trong game: /omnipets anim <id>; giới hạn chống lag: animation.max-active
