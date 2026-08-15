-- Ziji V1：冻结基金账户、消费贷款的 account_type 机器编码，并约束账户 class/type 合法矩阵。
-- CHG-ACC-001 只替换 V002 自动生成的 account_type CHECK，不改表结构，也不回填 OTHER。

-- 删除 V002 未命名 CHECK。PostgreSQL 自动名称为 accounts_account_type_check。
ALTER TABLE accounts
    DROP CONSTRAINT accounts_account_type_check;

-- 显式枚举必须包含 FUND 与 CONSUMER_LOAN。ADD CONSTRAINT 会校验既有行，发现非法数据时迁移失败。
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_account_type_values
        CHECK (account_type IN (
            'BANK', 'WECHAT', 'ALIPAY', 'CASH',
            'BROKERAGE', 'FUND',
            'CREDIT_CARD', 'LOAN', 'CONSUMER_LOAN',
            'OTHER'
        ));

-- 大类与子类型合法矩阵。OTHER 只能表示对应大类的“其他”，不得代替基金账户或消费贷款。
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_class_type_pair
        CHECK (
            (account_class = 'ASSET'
                AND account_type IN ('BANK', 'WECHAT', 'ALIPAY', 'CASH', 'OTHER'))
            OR (account_class = 'INVESTMENT'
                AND account_type IN ('BROKERAGE', 'FUND', 'OTHER'))
            OR (account_class = 'LIABILITY'
                AND account_type IN ('CREDIT_CARD', 'LOAN', 'CONSUMER_LOAN', 'OTHER'))
        );

COMMENT ON CONSTRAINT ck_accounts_account_type_values ON accounts IS
    '账户子类型机器编码。基金账户=FUND，消费贷款=CONSUMER_LOAN；OTHER 不得代替这两类。';
COMMENT ON CONSTRAINT ck_accounts_class_type_pair ON accounts IS
    '账户大类与子类型合法矩阵。ASSET=BANK/WECHAT/ALIPAY/CASH/OTHER；INVESTMENT=BROKERAGE/FUND/OTHER；LIABILITY=CREDIT_CARD/LOAN/CONSUMER_LOAN/OTHER。既有 OTHER 行保持 OTHER。';
