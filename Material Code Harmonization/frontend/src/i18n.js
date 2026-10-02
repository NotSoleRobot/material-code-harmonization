import i18n from "i18next";
import { initReactI18next } from "react-i18next";

export const resources = {
  en: {
    translation: {
      nav: {
        dashboard: "Dashboard",
        myMaterials: "My Materials",
        catalog: "Catalog",
        ingest: "Ingestion",
        review: "Review Queue",
        publish: "Publication",
        compare: "Comparison",
        audit: "Audit Trail",
        adminUsers: "Users",
        logout: "Sign Out",
      },
      roles: {
        OPERATOR: "CPSE Operator",
        REVIEWER: "Commodity Reviewer",
        SENIOR_REVIEWER: "Senior Catalog Approver",
        ADMIN: "Central Administrator",
      },
      auth: {
        email: "Email Address",
        password: "Password",
        signInBtn: "Sign In to Portal",
        signingIn: "Authenticating…",
        invalidCreds: "Invalid email or password.",
      },
      dashboard: {
        title: "Dashboard",
        subtitle: "A clear overview of materials, matching progress, and items that need attention.",
        totalMaterials: "Materials",
        totalGroups: "Published Codes",
        dedupRate: "Deduplication Rate",
        dedupFormula: "Duplicates Eliminated / Materials Reviewed",
        estimatedSavings: "Estimated Annual Savings",
        pendingReview: "Awaiting Review",
        confirmedMappings: "Confirmed Mappings",
        rejectedMappings: "Rejected Items",
        unmatchedItems: "Unmatched Pool",
        rateContractsTitle: "Shared Purchase Candidates",
        rateContractsSub: "Matched materials used by more than one organization.",
        priceVarianceTitle: "Price Differences",
        priceVarianceSub: "Price differences found across matching material records.",
        batchHarmonizeBtn: "Run Batch Harmonization",
      },
      catalog: {
        searchTitle: "Catalog",
        searchSub: "Search existing materials and codes before submitting a new request.",
        unifiedTitle: "Unified Catalog",
        unifiedSub: "Browse published materials, provisional matches, and their source records.",
        searchPlaceholder: "Search by national code, description, standard, or CPSE material code…",
        allCategories: "All Categories",
        exportBtn: "Export",
        requestCodeBtn: "Submit material",
        emptySearch: "No matching material records were found.",
        provisionalBadge: "Provisional Reference",
        activeBadge: "Active Master Code",
        viewDetails: "View Details",
      },
      review: {
        confHigh: "High Confidence",
        confMid: "Medium Confidence",
        confLow: "Low Confidence",
      },
      common: {
        error: "An error occurred",
        retry: "Retry",
        cancel: "Cancel",
        copyCode: "Copy Code",
        copied: "Copied",
      },
    },
  },
};

i18n
  .use(initReactI18next)
  .init({
    resources,
    lng: "en",
    fallbackLng: "en",
    interpolation: { escapeValue: false },
  });

export default i18n;
