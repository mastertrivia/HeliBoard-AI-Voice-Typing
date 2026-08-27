package helium314.keyboard.latin 
 
import org.junit.Assert.assertEquals 
import org.junit.Assert.assertNull 
import org.junit.Assert.assertSame 
import org.junit.Test 
 
class PredictionProviderContractTest { 
    @Test fun resolverChoosesHindiForDevanagari() { 
        assertSame(HindiPredictionProviderAdapter, PredictionProviderResolver.resolve("hindi")) 
    } 
 
    @Test fun resolverChoosesEnglishForDeshEnglish() { 
        assertSame(EnglishPredictionProviderAdapter, PredictionProviderResolver.resolve("desh_english")) 
    } 
 
    @Test fun resolverReturnsNullFallback() { 
        assertNull(PredictionProviderResolver.resolve("qwerty")) 
    } 
 
    @Test fun normalizerRemovesBlankCandidates() { 
        assertEquals(listOf("word"), ProviderCandidateNormalizer.normalize(listOf(ProviderCandidate("   ", 1, CandidateProvenance.UNKNOWN), ProviderCandidate("word", 2, CandidateProvenance.UNKNOWN))).map { it.text }) 
    } 
 
    @Test fun normalizerChoosesStrongerEnglishDuplicate() { 
        val result = ProviderCandidateNormalizer.normalize(listOf(ProviderCandidate("word", 1, CandidateProvenance.MAIN_DICTIONARY, "main"), ProviderCandidate("word", 3, CandidateProvenance.LEARNED_DICTIONARY, "history"))) 
        assertEquals(3, result.single().score) 
        assertEquals("history", result.single().sourceId) 
    } 
 
    @Test fun normalizerPreservesHindiProvenanceAndSourceId() { 
        val candidate = ProviderCandidate("हिंदी", 999999, CandidateProvenance.HINDI_NATIVE, "desh-hindi-native:1") 
        assertEquals(candidate, ProviderCandidateNormalizer.normalize(listOf(candidate)).single()) 
    } 
} 
