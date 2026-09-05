/**
 * 诊断控制台样例数据。
 *
 * 仅在占位接口不可达时使用。使用该数据的分区会被标记为「样例」，
 * 并禁用采纳/驳回等提交类操作，避免样例被当作真实诊断结果。
 */
const DIAGNOSIS_SAMPLE_PROJECTS = [
    {
        id: 'cangqiong-waimai',
        name: '苍穹外卖平台',
        diagnosable: true,
        reason: '',
        alert_count: 2
    },
    {
        id: 'payment-gateway',
        name: '支付网关',
        diagnosable: true,
        reason: '',
        alert_count: 0
    },
    {
        id: 'legacy-report-center',
        name: '报表中心（历史系统）',
        diagnosable: false,
        reason: '未登记项目档案，缺少日志主题与指标前缀',
        alert_count: 0
    }
];

const DIAGNOSIS_SAMPLE_SUMMARIES = {
    'cangqiong-waimai': {
        project_id: 'cangqiong-waimai',
        project_name: '苍穹外卖平台',
        metrics: [
            {
                name: '下单接口 P99 耗时',
                value: 2180,
                unit: 'ms',
                threshold: 800,
                threshold_direction: 'upper',
                meaning: '用户提交订单到返回结果的端到端耗时，超阈值意味着下单卡顿',
                available: true
            },
            {
                name: '订单支付成功率',
                value: 91.4,
                unit: '%',
                threshold: 98,
                threshold_direction: 'lower',
                meaning: '支付回调成功占比，下跌通常指向支付网关或回调链路异常',
                available: true
            },
            {
                name: '库存扣减失败次数',
                value: 37,
                unit: '次/5min',
                threshold: 5,
                threshold_direction: 'upper',
                meaning: '分布式锁竞争或库存不足导致的扣减失败，直接影响履约',
                available: true
            },
            {
                name: '骑手派单延迟',
                value: 0,
                unit: 's',
                threshold: 60,
                threshold_direction: 'upper',
                meaning: '订单生成到派单完成的延迟',
                available: false
            }
        ],
        alerts: [
            {
                alert_name: 'OrderApiSlowResponse',
                severity: 'critical',
                active_at_display: '2026-08-08 18:42:11',
                duration: '23m'
            },
            {
                alert_name: 'PaymentCallbackFailureRate',
                severity: 'warning',
                active_at_display: '2026-08-08 18:55:03',
                duration: '10m'
            }
        ],
        last_change: {
            available: true,
            at_display: '2026-08-08 18:30:47',
            change_id: 'build-1042',
            summary: 'order-service 发布 v2.7.3，改动订单超时时间与库存扣减重试策略'
        }
    },
    'payment-gateway': {
        project_id: 'payment-gateway',
        project_name: '支付网关',
        metrics: [
            {
                name: '支付渠道平均响应',
                value: 210,
                unit: 'ms',
                threshold: 500,
                threshold_direction: 'upper',
                meaning: '调用第三方支付渠道的平均耗时',
                available: true
            },
            {
                name: '对账差异笔数',
                value: 0,
                unit: '笔/h',
                threshold: 1,
                threshold_direction: 'upper',
                meaning: '与渠道账单不一致的订单数量',
                available: true
            }
        ],
        alerts: [],
        last_change: {
            available: false,
            at_display: '',
            change_id: '',
            summary: ''
        }
    }
};
