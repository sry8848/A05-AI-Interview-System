package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
""";

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;

    public StructuredOutputInvoker(
        @Value("${app.ai.structured-max-attempts:2}") int maxAttempts,
        @Value("${app.ai.structured-include-last-error:true}") boolean includeLastErrorInRetryPrompt
    ) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.includeLastErrorInRetryPrompt = includeLastErrorInRetryPrompt;
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        Exception lastError = null;// 用于记录最后一次错误
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {// 尝试次数
            String attemptSystemPrompt = attempt == 1
                ? systemPromptWithFormat
                : buildRetrySystemPrompt(systemPromptWithFormat, lastError);
            try {
                return chatClient.prompt()
                    .system(attemptSystemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(outputConverter);
//                不管你的业务有多复杂，代码永远严格遵循以下四个阶段，顺序绝不会乱：
//                阶段一：起手式（拔枪）
//                永远只有这一个固定写法，作用是开启一次新的对话任务。
//                写法： .prompt()
//                你的动作： 敲完这个词，不要管其他的，直接进入下一步。
//
//                阶段二：装填弹药（塞入你要说的话）
//                这里把你要告诉 AI 的信息装进去。通常分两部分：
//                写法 A（可选）： .system("你的系统人设说明") -> 告诉 AI 它是谁（比如面试官）。
//                写法 B（必填）： .user("用户的提问") -> 告诉 AI 它要干嘛（比如“给我出题”）。
//
//                阶段三：发射（请求网络）
//                写法 A（常用）： .call() -> 阻塞等待，直到大模型把整段话全部想完、写完，一次性拿回来。
//                写法 B（高级）： .stream() -> 像打字机一样，大模型想出一个字就吐给你一个字。
//
//                阶段四：收拾战利品（提取结果）
//                大模型返回的数据是一个巨大的包裹（包含消耗了多少钱、用时多久等），你需要拆开包裹拿你要的东西。
//                写法 A（拿文字）： .content() -> 只要它说的那段纯文字，返回 String。
//                写法 B（拿对象）： .entity(你要的类.class) -> 让框架自动把文字转换成你写好的 Java DTO 对象。
            } catch (Exception e) {
                lastError = e;
                log.warn("{}结构化解析失败，准备重试: attempt={}, error={}", logContext, attempt, e.getMessage());
            }
        }

        throw new BusinessException(
            errorCode,
            errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n")
            .append(STRICT_JSON_INSTRUCTION)
            .append("\n上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > 200) {
            return oneLine.substring(0, 200) + "...";
        }
        return oneLine;
    }
}
