import i18n from "i18next";
import { initReactI18next } from "react-i18next";

export const resources = {
  en: {
    translation: {
      nav: {
        dashboard: "Dashboard & Analytics",
        myMaterials: "My Materials",
        catalog: "Unified Catalog",
        ingest: "Ingestion Wizard",
        review: "Review Queue",
        publish: "Publication & Minting",
        compare: "Material Comparison",
        audit: "Audit Trail",
        adminUsers: "User Management",
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
        title: "Material Master Analytics",
        subtitle: "Platform-wide harmonization metrics, deduplication savings, and inter-CPSE demand aggregation.",
        totalMaterials: "Total Ingested Materials",
        totalGroups: "Canonical National Codes",
        dedupRate: "Deduplication Rate",
        dedupFormula: "Duplicates Eliminated / Materials Reviewed",
        estimatedSavings: "Estimated Annual Savings",
        pendingReview: "Awaiting Review",
        confirmedMappings: "Confirmed Mappings",
        rejectedMappings: "Rejected Items",
        unmatchedItems: "Unmatched Pool",
        rateContractsTitle: "Joint GeM Rate-Contract Candidates",
        rateContractsSub: "Harmonized items procured independently by two or more CPSEs and suitable for consolidated procurement.",
        priceVarianceTitle: "Cross-CPSE Procurement Price Variance",
        priceVarianceSub: "Price disparity across CPSEs for identical physical materials.",
        batchHarmonizeBtn: "Run Batch Harmonization",
      },
      catalog: {
        searchTitle: "National Catalog Search",
        searchSub: "Search existing national codes before creating new material requests.",
        unifiedTitle: "Unified Material Master Catalog",
        unifiedSub: "Canonical material codes, standardized specifications, and CPSE cross-references.",
        searchPlaceholder: "Search by national code, description, standard, or CPSE material code…",
        allCategories: "All Categories",
        exportBtn: "Export Catalog",
        requestCodeBtn: "Request New National Code",
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
